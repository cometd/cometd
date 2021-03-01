/*
 * Copyright (c) 2008-2021 the original author or authors.
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
package org.webtide.demo.auction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.servlet.ServletContext;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.oort.Oort;
import org.cometd.oort.Seti;
import org.cometd.oort.SetiServlet;
import org.cometd.server.AbstractService;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuctionChatService extends AbstractService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionChatService.class);

    // A map(channel, map(userName, clientId))
    private final ConcurrentMap<String, Set<String>> _members = new ConcurrentHashMap<>();
    private final Oort _oort;
    private final Seti _seti;

    public AuctionChatService(ServletContext context) {
        super((BayeuxServer)context.getAttribute(BayeuxServer.ATTRIBUTE), "chat");

        _oort = (Oort)context.getAttribute(Oort.OORT_ATTRIBUTE);
        if (_oort == null) {
            throw new RuntimeException("Missing " + Oort.OORT_ATTRIBUTE + " from " + ServletContext.class.getSimpleName() + "; " +
                    "is an Oort servlet declared in web.xml ?");
        }
        _seti = (Seti)context.getAttribute(Seti.SETI_ATTRIBUTE);
        if (_seti == null) {
            throw new RuntimeException("Missing " + Seti.SETI_ATTRIBUTE + " from " + ServletContext.class.getSimpleName() + "; " +
                    "is " + SetiServlet.class.getSimpleName() + " declared in web.xml ?");
        }

        getBayeux().createChannelIfAbsent("/auction/chat/**", channel -> channel.addAuthorizer(GrantAuthorizer.GRANT_ALL));
        getBayeux().createChannelIfAbsent("/service/auction/chat", channel -> channel.addAuthorizer(GrantAuthorizer.GRANT_ALL));
        addService("/auction/chat/**", "trackMembers");
        addService("/service/auction/chat", "privateChat");
    }

    public void trackMembers(ServerSession joiner, ServerMessage message) {
        String channelName = message.getChannel();
        Object data = message.getData();
        if (data instanceof Object[]) {
            Set<String> members = _members.get(channelName);
            if (members == null) {
                Set<String> newMembers = new HashSet<>();
                members = _members.putIfAbsent(channelName, newMembers);
                if (members == null) {
                    members = newMembers;
                }
            }
            boolean added = false;
            for (Object user : (Object[])data) {
                added |= members.add(user.toString());
            }
            if (added) {
                LOGGER.info("Members: {}", members);
                // Broadcast the members to all existing members
                getBayeux().getChannel(channelName).publish(getServerSession(), members, Promise.noop());
            }
        } else if (data instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>)data;

            if (Boolean.TRUE.equals(map.get("join"))) {

                Set<String> members = _members.get(channelName);
                if (members == null) {
                    Set<String> newMembers = new HashSet<>();
                    members = _members.putIfAbsent(channelName, newMembers);
                    if (members == null) {
                        members = newMembers;
                    }
                }

                String userName = (String)map.get("user");

                members.add(userName);

                if (!_oort.isOort(joiner)) {
                    _seti.associate(userName, joiner);
                }

                joiner.addListener((ServerSession.RemovedListener)(s, m, t) -> {
                    if (!_oort.isOort(joiner)) {
                        _seti.disassociate(userName, s);
                    }
                    if (t) {
                        ServerChannel channel = getBayeux().getChannel(channelName);
                        if (channel != null) {
                            Map<String, Object> leave = new HashMap<>();
                            leave.put("leave", Boolean.TRUE);
                            leave.put("user", userName);
                            channel.publish(null, leave, Promise.noop());
                        }
                    }
                });

                LOGGER.info("Members: {}", members);
                // Broadcast the members to all existing members
                getBayeux().getChannel(channelName).publish(getServerSession(), members, Promise.noop());

            }

            if (Boolean.TRUE.equals(map.get("leave"))) {
                Set<String> members = _members.get(channelName);
                if (members == null) {
                    Set<String> newMembers = new HashSet<>();
                    members = _members.putIfAbsent(channelName, newMembers);
                    if (members == null) {
                        members = newMembers;
                    }
                }

                String userName = (String)map.get("user");
                members.remove(userName);

                LOGGER.info("Members: {}", members);
                // Broadcast the members to all existing members
                getBayeux().getChannel(channelName).publish(getServerSession(), members, Promise.noop());
            }
        }
    }

    public void privateChat(ServerSession source, ServerMessage message) {
        Map<String, Object> data = message.getDataAsMap();
        String toUid = (String)data.get("peer");
        String toChannel = (String)data.get("room");
        source.deliver(source, toChannel, data, Promise.noop());
        _seti.sendMessage(toUid, toChannel, data);
    }
}
