/**
 *
 */
package org.cometd.examples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.inject.Inject;

import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.java.annotation.Configure;
import org.cometd.java.annotation.Listener;
import org.cometd.java.annotation.Service;
import org.cometd.java.annotation.Session;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.cometd.server.filter.DataFilter;
import org.cometd.server.filter.DataFilterMessageListener;
import org.cometd.server.filter.JSONDataFilter;
import org.cometd.server.filter.NoMarkupFilter;

@Service("chat")
public class ChatService
{
    private final ConcurrentMap<String, Map<String, String>> _members = new ConcurrentHashMap<String, Map<String, String>>();
    @Inject
    private BayeuxServer _bayeux;
    @Session
    private ServerSession _session;

    @Configure ({"/chat/**","/members/**"})
    protected void configureChatStarStar(ConfigurableServerChannel channel)
    {
        DataFilterMessageListener noMarkup = new DataFilterMessageListener(_bayeux,new NoMarkupFilter(),new BadWordFilter());
        channel.addListener(noMarkup);
        channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
    }

    @Configure ("/service/members")
    protected void configureMembers(ConfigurableServerChannel channel)
    {
        channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);
        channel.setPersistent(true);
    }

    @Listener("/service/members")
    public void handleMembership(ServerSession client, ServerMessage message)
    {
        Map<String, Object> data = message.getDataAsMap();
        final String room = ((String)data.get("room")).substring("/chat/".length());
        Map<String, String> roomMembers = _members.get(room);
        if (roomMembers == null)
        {
            Map<String, String> new_room = new ConcurrentHashMap<String, String>();
            roomMembers = _members.putIfAbsent(room, new_room);
            if (roomMembers == null) roomMembers = new_room;
        }
        final Map<String, String> members = roomMembers;
        String userName = (String)data.get("user");
        members.put(userName, client.getId());
        client.addListener(new ServerSession.RemoveListener()
        {
            public void removed(ServerSession session, boolean timeout)
            {
                members.values().remove(session.getId());
                broadcastMembers(room,members.keySet());
            }
        });

        broadcastMembers(room,members.keySet());
    }

    private void broadcastMembers(String room, Set<String> members)
    {
        // Broadcast the new members list
        ClientSessionChannel channel = _session.getLocalSession().getChannel("/members/"+room);
        channel.publish(members);
    }
    
    @Configure ("/service/privatechat")
    protected void configurePrivateChat(ConfigurableServerChannel channel)
    {
        DataFilterMessageListener noMarkup = new DataFilterMessageListener(_bayeux,new NoMarkupFilter(),new BadWordFilter());
        channel.setPersistent(true);
        channel.addListener(noMarkup);
        channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);
    }

    @Listener("/service/privatechat")
    protected void privateChat(ServerSession client, ServerMessage message)
    {
        Map<String,Object> data = message.getDataAsMap();
        String room = ((String)data.get("room")).substring("/chat/".length());
        Map<String, String> membersMap = _members.get(room);
        if (membersMap==null)
        {
            Map<String,String>new_room=new ConcurrentHashMap<String, String>();
            membersMap=_members.putIfAbsent(room,new_room);
            if (membersMap==null)
                membersMap=new_room;
        }
        String[] peerNames = ((String)data.get("peer")).split(",");
        ArrayList<ServerSession> peers = new ArrayList<ServerSession>(peerNames.length);

        for (String peerName : peerNames)
        {
            String peerId = membersMap.get(peerName);
            if (peerId!=null)
            {
                ServerSession peer = _bayeux.getSession(peerId);
                if (peer!=null)
                    peers.add(peer);
            }
        }

        if (peers.size() > 0)
        {
            Map<String, Object> chat = new HashMap<String, Object>();
            String text=(String)data.get("chat");
            chat.put("chat", text);
            chat.put("user", data.get("user"));
            chat.put("scope", "private");
            ServerMessage.Mutable forward = _bayeux.newMessage();
            forward.setChannel("/chat/"+room);
            forward.setId(message.getId());
            forward.setData(chat);

            // test for lazy messages
            if (text.lastIndexOf("lazy")>0)
                forward.setLazy(true);

            for (ServerSession peer : peers)
                if (peer!=client)
                    peer.deliver(_session, forward);
            client.deliver(_session, forward);
        }
    }

    class BadWordFilter extends JSONDataFilter
    {
        @Override
        protected Object filterString(String string)
        {
            if (string.indexOf("dang")>=0)
                throw new DataFilter.Abort();
            return string;
        }
    }
}
