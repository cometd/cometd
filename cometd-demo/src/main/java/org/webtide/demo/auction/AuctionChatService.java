/*
 * Copyright (c) 2010 the original author or authors.
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

/**
 *
 */

package org.webtide.demo.auction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.servlet.ServletContext;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.oort.Oort;
import org.cometd.oort.Seti;
import org.cometd.oort.SetiServlet;
import org.cometd.server.AbstractService;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.eclipse.jetty.util.log.Log;

public class AuctionChatService extends AbstractService
{
    /**
     * A map(channel, map(userName, clientId))
     */
    private final ConcurrentMap<String, Set<String>> _members = new ConcurrentHashMap<String, Set<String>>();
    private Oort _oort;
    private Seti _seti;

    public AuctionChatService(ServletContext context)
    {
        super((BayeuxServer)context.getAttribute(BayeuxServer.ATTRIBUTE), "chat");

        _oort = (Oort)context.getAttribute(Oort.OORT_ATTRIBUTE);
        if (_oort == null)
            throw new RuntimeException("Missing " + Oort.OORT_ATTRIBUTE + " from " + ServletContext.class.getSimpleName() + "; " +
                    "is an Oort servlet declared in web.xml ?");
        _seti = (Seti)context.getAttribute(Seti.SETI_ATTRIBUTE);
        if (_seti == null)
            throw new RuntimeException("Missing " + Seti.SETI_ATTRIBUTE + " from " + ServletContext.class.getSimpleName() + "; " +
                    "is " + SetiServlet.class.getSimpleName() + " declared in web.xml ?");

        getBayeux().createIfAbsent("/auction/chat/**", new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
            }
        });
        getBayeux().createIfAbsent("/service/auction/chat", new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
            }
        });
        addService("/auction/chat/**", "trackMembers");
        addService("/service/auction/chat", "privateChat");
    }

    public void trackMembers(final ServerSession joiner, final String channelName, Object data, final String messageId)
    {
        if (data instanceof Object[])
        {
            Set<String> members = _members.get(channelName);
            if (members == null)
            {
                Set<String> newMembers = new HashSet<String>();
                members = _members.putIfAbsent(channelName, newMembers);
                if (members == null)
                    members = newMembers;
            }
            boolean added = false;
            for (Object user : (Object[])data)
                added |= members.add(user.toString());
            if (added)
            {
                Log.info("Members: " + members);
                // Broadcast the members to all existing members
                getBayeux().getChannel(channelName).publish(getServerSession(), members, messageId);
            }
        }
        else if (data instanceof Map)
        {
            Map<String, Object> map = (Map<String, Object>)data;

            if (Boolean.TRUE.equals(map.get("join")))
            {

                Set<String> members = _members.get(channelName);
                if (members == null)
                {
                    Set<String> newMembers = new HashSet<String>();
                    members = _members.putIfAbsent(channelName, newMembers);
                    if (members == null)
                        members = newMembers;
                }

                final String userName = (String)map.get("user");

                members.add(userName);

                if (!_oort.isOort(joiner))
                    _seti.associate(userName, joiner);

                joiner.addListener(new ServerSession.RemoveListener()
                {
                    public void removed(ServerSession session, boolean timeout)
                    {
                        if (!_oort.isOort(joiner))
                            _seti.disassociate(userName, session);
                        if (timeout)
                        {
                            ServerChannel channel = getBayeux().getChannel(channelName);
                            if (channel != null)
                            {
                                Map<String, Object> leave = new HashMap<String, Object>();
                                leave.put("leave", Boolean.TRUE);
                                leave.put("user", userName);
                                channel.publish(null, leave, null);
                            }
                        }
                    }
                });

                Log.info("Members: " + members);
                // Broadcast the members to all existing members
                getBayeux().getChannel(channelName).publish(getServerSession(), members, messageId);

            }

            if (Boolean.TRUE.equals(map.get("leave")))
            {
                Set<String> members = _members.get(channelName);
                if (members == null)
                {
                    Set<String> newMembers = new HashSet<String>();
                    members = _members.putIfAbsent(channelName, newMembers);
                    if (members == null)
                        members = newMembers;
                }

                String userName = (String)map.get("user");
                members.remove(userName);

                Log.info("Members: " + members);
                // Broadcast the members to all existing members
                getBayeux().getChannel(channelName).publish(getServerSession(), members, messageId);
            }
        }
    }

    public void privateChat(ServerSession source, String channel, Map<String, Object> data, String messageId)
    {
        String toUid = (String)data.get("peer");
        String toChannel = (String)data.get("room");
        source.deliver(source, toChannel, data, messageId);
        _seti.sendMessage(toUid, toChannel, data);
    }
}

