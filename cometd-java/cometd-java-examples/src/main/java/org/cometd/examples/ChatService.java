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

import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.annotation.CometdServer;
import org.cometd.server.annotation.CometdService;
import org.cometd.server.annotation.CometdSession;
import org.cometd.server.annotation.Configure;
import org.cometd.server.annotation.Subscription;
import org.cometd.server.filter.DataFilter;
import org.cometd.server.filter.DataFilterMessageListener;
import org.cometd.server.filter.JSONDataFilter;
import org.cometd.server.filter.NoMarkupFilter;

@CometdService(name="chat", seeOwn=false, threads=-1)
public class ChatService 
{
    private final ConcurrentMap<String, Map<String, String>> _members = new ConcurrentHashMap<String, Map<String, String>>();
    
    @CometdServer
    private BayeuxServer _bayeux;

    @CometdSession
    private ServerSession _session;
    
    public ChatService()
    {
    }

    @Configure (channels={"/service/privatechat","/chat/**"}, ifAbsent=true )
    public void configureChannel(ConfigurableServerChannel channel)
    {
        channel.setPersistent(true);
        channel.addListener(new DataFilterMessageListener(_bayeux,new NoMarkupFilter(),new BadWordFilter()));
    }
    
    @Subscription (channels="/service/members")
    public void handleMembership(ServerSession client, Map<String, Object> data)
    {
        String room = (String)data.get("room");
        Map<String, String> roomMembers = _members.get(room);
        if (roomMembers == null)
        {
            Map<String, String> newRoomMembers = new ConcurrentHashMap<String, String>();
            roomMembers = _members.putIfAbsent(room, newRoomMembers);
            if (roomMembers == null) roomMembers = newRoomMembers;
        }
        final Map<String, String> members = roomMembers;
        String userName = (String)data.get("user");
        members.put(userName, client.getId());
        client.addListener(new ServerSession.RemoveListener()
        {
            public void removed(ServerSession session, boolean timeout)
            {
                members.values().remove(session.getId());
                broadcastMembers(members.keySet());
            }
        });

        broadcastMembers(members.keySet());
    }

    private void broadcastMembers(Set<String> members)
    {
        // Broadcast the new members list
        ClientSessionChannel channel = _session.getLocalSession().getChannel("/chat/members");
        channel.publish(members);
    }

    @Subscription (channels="/service/privatechat")
    public void privateChat(ServerSession client, ServerMessage message)
    {
        Map<String,Object> data = message.getDataAsMap();
        String room = (String)data.get("room");
        Map<String, String> membersMap = _members.get(room);
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
            forward.setChannel(room);
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
