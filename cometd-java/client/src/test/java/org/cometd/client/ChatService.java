package org.cometd.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.cometd.Bayeux;
import org.cometd.Channel;
import org.cometd.Client;
import org.cometd.RemoveListener;
import org.cometd.server.BayeuxService;

public class ChatService extends BayeuxService
{
    private final ConcurrentMap<String, Map<String, String>> _members = new ConcurrentHashMap<String, Map<String, String>>();

    public ChatService(Bayeux bayeux)
    {
        super(bayeux, "chat");
        subscribe("/service/members", "handleMembership");
        subscribe("/service/privatechat", "privateChat");
    }

    public void handleMembership(Client client, Map<String, Object> data)
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
        client.addListener(new RemoveListener()
        {
            public void removed(String clientId, boolean timeout)
            {
                members.values().remove(clientId);
                broadcastMembers(members.keySet());
            }
        });
        broadcastMembers(members.keySet());
    }

    private void broadcastMembers(Set<String> members)
    {
        // Broadcast the new members list
        Channel channel = getBayeux().getChannel("/chat/members", false);
        if (channel != null)
            channel.publish(getClient(), members, null);
    }

    public void privateChat(Client client, Map<String, Object> data)
    {
        String roomName = (String)data.get("room");
        Map<String, String> membersMap = _members.get(roomName);
        String[] peerNames = ((String)data.get("peer")).split(",");
        ArrayList<Client> peers = new ArrayList<Client>(peerNames.length);

        for (String peerName : peerNames)
        {
            String peerId = membersMap.get(peerName);
            if (peerId!=null)
            {
                Client peer = getBayeux().getClient(peerId);
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
            for (Client peer : peers)
                peer.deliver(client, roomName, message, null);
            client.deliver(getClient(), roomName, message, null);
        }
    }
}
