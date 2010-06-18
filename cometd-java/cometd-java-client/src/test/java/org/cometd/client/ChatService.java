package org.cometd.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;

public class ChatService extends AbstractService
{
    private final ConcurrentMap<String, Map<String, String>> _members = new ConcurrentHashMap<String, Map<String, String>>();

    public ChatService(BayeuxServer bayeux)
    {
        super(bayeux,"chat");
        addService("/service/members", "handleMembership");
        addService("/service/privatechat", "privateChat");
    }

    public void handleMembership(ServerSession session, Map<String, Object> data)
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
        members.put(userName, session.getId());

        session.addListener(new ServerSession.RemoveListener()
        {
            @Override
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
        ServerChannel channel = getBayeux().getChannel("/chat/members");
        if (channel != null)
            channel.publish(getServerSession(), members, null);
    }

    public void privateChat(ServerSession session, Map<String, Object> data)
    {
        String roomName = (String)data.get("room");
        Map<String, String> membersMap = _members.get(roomName);
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
            Map<String, Object> message = new HashMap<String, Object>();
            message.put("chat", data.get("chat"));
            message.put("user", data.get("user"));
            message.put("scope", "private");
            for (ServerSession peer : peers)
                peer.deliver(getLocalSession(),roomName, message, null);

            getServerSession().deliver(getLocalSession(), roomName, message, null);
        }
    }
}
