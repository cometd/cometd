/*
 * Copyright (c) 2008-2020 the original author or authors.
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

package org.cometd.oort;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.server.Configure;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.cometd.server.filter.DataFilter;
import org.cometd.server.filter.DataFilterMessageListener;
import org.cometd.server.filter.JSONDataFilter;
import org.cometd.server.filter.NoMarkupFilter;

@Service("chat")
public class OortChatService {
    private final ConcurrentMap<String, Set<String>> _members = new ConcurrentHashMap<>();
    @Inject
    private BayeuxServer _bayeux;
    @Inject
    private Oort _oort;
    @Inject
    private Seti _seti;
    @Session
    private ServerSession _session;

    @PostConstruct
    private void init() {
        _oort.observeChannel("/chat/**");
        _oort.observeChannel("/members/**");
    }

    @PreDestroy
    private void destroy() {
        _oort.deobserveChannel("/members/**");
        _oort.deobserveChannel("/chat/**");
    }

    @Configure({"/chat/**", "/members/**"})
    private void configureChatStarStar(ConfigurableServerChannel channel) {
        DataFilterMessageListener noMarkup = new DataFilterMessageListener(_bayeux, new NoMarkupFilter(), new BadWordFilter());
        channel.addListener(noMarkup);
        channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
    }

    @Configure("/service/privatechat")
    private void configurePrivateChat(ConfigurableServerChannel channel) {
        DataFilterMessageListener noMarkup = new DataFilterMessageListener(_bayeux, new NoMarkupFilter(), new BadWordFilter());
        channel.setPersistent(true);
        channel.addListener(noMarkup);
        channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);
    }

    @Configure("/service/members")
    private void configureMembers(ConfigurableServerChannel channel) {
        channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);
        channel.setPersistent(true);
    }

    private Set<String> getMemberList(String room) {
        Set<String> members = _members.get(room);
        if (members == null) {
            Set<String> newMembers = Collections.newSetFromMap(new ConcurrentHashMap<>());
            members = _members.putIfAbsent(room, newMembers);
            if (members == null) {
                members = newMembers;
            }
        }
        return members;
    }

    @Listener("/service/members")
    public void handleMembership(ServerSession client, ServerMessage message) {
        Map<String, Object> data = message.getDataAsMap();
        String room = ((String)data.get("room")).substring("/chat/".length());
        String userName = (String)data.get("user");

        Set<String> members = getMemberList(room);
        members.add(userName);
        client.addListener((ServerSession.RemovedListener)(s, m, t) -> {
            if (!_oort.isOort(client)) {
                _seti.disassociate(userName, s);
            }
            members.remove(userName);
            broadcastMembers(room, members);
        });

        if (!_oort.isOort(client)) {
            _seti.associate(userName, client);
        }

        broadcastMembers(room, members);
    }

    @Listener("/members/**")
    public void handleMembershipBroadcast(ServerSession client, ServerMessage message) {
        String room = message.getChannel().substring("/members/".length());

        Object data = message.getData();
        Object[] newMembers = data instanceof List ? ((List<?>)data).toArray() : (Object[])data;

        Collection<String> members = getMemberList(room);
        boolean added = false;
        for (Object o : newMembers) {
            added |= members.add(o.toString());
        }

        if (added) {
            broadcastMembers(room, members);
        }
    }

    private void broadcastMembers(String room, Collection<String> members) {
        // Broadcast the new members list
        ClientSessionChannel channel = _session.getLocalSession().getChannel("/members/" + room);
        channel.publish(members);
    }

    @Listener("/service/privatechat")
    public void privateChat(ServerSession client, ServerMessage message) {
        Map<String, Object> data = message.getDataAsMap();
        String toUid = (String)data.get("peer");
        String toChannel = (String)data.get("room");
        data.put("scope", "private");
        data.put("user", data.get("user") + "->" + toUid);
        client.deliver(client, toChannel, data, Promise.noop());
        _seti.sendMessage(toUid, toChannel, data);
    }

    private static class BadWordFilter extends JSONDataFilter {
        @Override
        protected Object filterString(ServerSession session, ServerChannel channel, String string) {
            if (string.contains("dang")) {
                throw new DataFilter.AbortException();
            }
            return string;
        }
    }
}

