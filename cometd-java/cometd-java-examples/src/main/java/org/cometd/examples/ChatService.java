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
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.filter.DataFilterMessageListener;
import org.cometd.server.filter.NoMarkupFilter;

public class ChatService extends AbstractService
{
    private final ConcurrentMap<String, Map<String, String>> _members = new ConcurrentHashMap<String, Map<String, String>>();

    public ChatService(BayeuxServer bayeux)
    {
        super(bayeux, "chat");
        final DataFilterMessageListener noMarkup = new DataFilterMessageListener(new NoMarkupFilter());
        ServerChannel.Initializer initNoMarkup = new ServerChannel.Initializer()
        {
            @Override
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setPersistent(true);
                channel.addListener(noMarkup);
            }
        };

        if (!bayeux.createIfAbsent("/chat/**",initNoMarkup) ||
            !bayeux.createIfAbsent("/service/privatechat",initNoMarkup))
            throw new IllegalStateException();

        addService("/service/members", "handleMembership");
        addService("/service/privatechat", "privateChat");
    }

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
        ClientSessionChannel channel = getLocalSession().getChannel("/chat/members");
        channel.publish(members);
    }

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
                ServerSession peer = getBayeux().getSession(peerId);
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
            ServerMessage.Mutable forward = getBayeux().newMessage();
            forward.setChannel(room);
            forward.setId(message.getId());
            forward.setData(chat);

            // test for lazy messages
            if (text.lastIndexOf("lazy")>0)
                forward.setLazy(true);


            for (ServerSession peer : peers)
                peer.deliver(getClient().getServerSession(),forward);
            client.deliver(getClient().getServerSession(), forward);
        }
    }
}
