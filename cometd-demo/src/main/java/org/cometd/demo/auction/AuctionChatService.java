/*
 * Copyright (c) 2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.demo.auction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.cometd.annotation.Listener;
import org.cometd.annotation.Param;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.oort.Oort;
import org.cometd.oort.OortList;
import org.cometd.oort.OortObject;
import org.cometd.oort.OortObjectFactories;
import org.cometd.oort.OortObjectMergers;
import org.cometd.oort.Seti;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AuctionChatService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionChatService.class);

    private final ConcurrentMap<String, OortList<String>> _members = new ConcurrentHashMap<>();
    @Inject
    private BayeuxServer _bayeux;
    @Inject
    private Oort _oort;
    @Inject
    private Seti _seti;
    @Session
    private ServerSession _session;

    @PostConstruct
    public void construct() {
        // The message channel is clustered.
        // The members channel is not clustered.
        _oort.observeChannel("/auction/chat/*");
    }

    @PreDestroy
    public void destroy() {
        _oort.deobserveChannel("/auction/chat/*");
    }

    @Listener("/service/auction/chat/{id}")
    public void chat(ServerSession session, ServerMessage message, @Param("id") String id) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("received chat message {}", message);
        }
        Map<String, Object> data = message.getDataAsMap();
        String user = (String)data.get("user");
        String peer = (String)data.get("peer");
        if (peer == null) {
            if (Boolean.TRUE.equals(data.get("join"))) {
                joinChat(session, id, user, data);
            } else if (Boolean.TRUE.equals(data.get("leave"))) {
                leaveChat(session, id, user, data);
            } else {
                // Re-broadcast the message on the cluster.
                // Here we have the chance to filter the message text if necessary.
                broadcastMessage(id, data);
            }
        } else {
            privateChat(session, id, user, peer, data);
        }
    }

    private void joinChat(ServerSession session, String id, String user, Map<String, Object> data) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("user {} joined chat for item {}", user, id);
        }
        // Associate the user in the cluster via Seti.
        _seti.associate(user, session);
        session.addListener((ServerSession.RemovedListener)(s, m, t) -> {
            leaveChat(session, id, user, Map.of("user", user, "leave", true, "chat", user + " has left"));
        });
        // Record the user in the chat for that particular item.
        OortList<String> members = _members.computeIfAbsent(id, key -> {
            OortList<String> list = new OortList<>(_oort, "chat-members-" + id, OortObjectFactories.forConcurrentList());
            // The delta listener is necessary when a user on another node joins the chat:
            // its whole member list will be converted in add events handled by the MembersListener.
            list.addListener(new OortList.DeltaListener<>(list));
            // This listener broadcasts the members list to local clients.
            list.addElementListener(new MembersListener(id, list));
            LifeCycle.start(list);
            return list;
        });
        members.addAndShare(null, user);
        // Broadcast on the cluster that the user joined.
        broadcastMessage(id, data);
    }

    private void leaveChat(ServerSession session, String id, String user, Map<String, Object> data) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("user {} left chat for item {}", user, id);
        }
        _seti.disassociate(user, session);
        OortList<String> members = _members.get(id);
        if (members != null) {
            members.removeAndShare(null, user);
        }
        // Broadcast on the cluster that the user left.
        broadcastMessage(id, data);
    }

    private void privateChat(ServerSession session, String id, String user, String peer, Map<String, Object> data) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("user {} sent private chat to {} for item {}", user, peer, id);
        }
        String channel = "/auction/chat/" + id;
        // Send it back for display on the broadcast channel.
        session.deliver(_session, channel, data, Promise.noop());
        // Send it to the peer via Seti.
        _seti.sendMessage(peer, channel, data);
    }

    private void broadcastMessage(String id, Map<String, Object> data) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("broadcast chat message {} for item {}", data, id);
        }
        // The broadcast chat channel must be clustered.
        // The channel may be null if it has been swept (for example user expired, and the sweeper
        // run before this method is called), but we must broadcast the message to the cluster.
        ServerChannel broadcast = _bayeux.createChannelIfAbsent("/auction/chat/" + id).getReference();
        broadcast.publish(_session, data, Promise.noop());
    }

    private void broadcastMembers(String id, List<String> members) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("broadcast chat members {} for item {}", members, id);
        }
        // This channel must not be clustered, since the clustering is handled by OortList.
        // Each node will broadcast to its remote clients, avoiding out-of-order
        // updates of the members list that would happen if the channel is clustered.
        ServerChannel broadcast = _bayeux.getChannel("/auction/chat/members/" + id);
        // The channel may be null if there are no local clients chatting for this item.
        if (broadcast != null) {
            broadcast.publish(_session, members, Promise.noop());
        }
    }

    private class MembersListener implements OortList.ElementListener<String> {
        private final AutoLock lock = new AutoLock();
        private final String id;
        private final OortList<String> list;

        private MembersListener(String id, OortList<String> list) {
            this.id = id;
            this.list = list;
        }

        @Override
        public void onAdded(OortObject.Info<List<String>> info, List<String> elements) {
            broadcast();
        }

        @Override
        public void onRemoved(OortObject.Info<List<String>> info, List<String> elements) {
            broadcast();
        }

        private void broadcast() {
            // This method is called concurrently from different nodes,
            // and also concurrently with respect to adds and removes.
            try (AutoLock ignored = lock.lock()) {
                List<String> members = list.merge(OortObjectMergers.listUnion());
                members.sort(null);
                broadcastMembers(id, members);
            }
        }
    }
}
