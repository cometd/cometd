package org.cometd.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.cometd.Bayeux;
import org.cometd.Channel;
import org.cometd.Client;
import org.cometd.RemoveListener;
import org.cometd.server.BayeuxService;
import org.cometd.server.ChannelImpl;
import org.eclipse.jetty.util.log.Log;

public class ChatService extends BayeuxService
{
    private final ConcurrentMap<String, Map<String, String>> _members = new ConcurrentHashMap<String, Map<String, String>>();

    public ChatService(Bayeux bayeux)
    {
        super(bayeux, "chat");
        subscribe("/chat/**", "trackMembers");
        subscribe("/service/privatechat", "privateChat");
    }
    
    public void trackMembers(final Client joiner, final String channelName, Map<String, Object> data, final String messageId)
    {
        if (Boolean.TRUE.equals(data.get("join")))
        {
            Map<String, String> membersMap = _members.get(channelName);
            if (membersMap == null)
            {
                Map<String, String> newMembersMap = new ConcurrentHashMap<String, String>();
                membersMap = _members.putIfAbsent(channelName, newMembersMap);
                if (membersMap == null) membersMap = newMembersMap;
            }
            
            final Map<String, String> members = membersMap;
            final String userName = (String)data.get("user");
            members.put(userName, joiner.getId());
            joiner.addListener(new RemoveListener()
            {
                public void removed(String clientId, boolean timeout)
                {
                    members.values().remove(clientId);
                    Log.info("members: " + members);
                    // Broadcast the members to all existing members
                    Channel channel = getBayeux().getChannel(channelName, false);
                    if (channel != null) channel.publish(getClient(), members.keySet(), messageId);
                }
            });

            Log.info("Members: " + members);
            // Broadcast the members to all existing members
            getBayeux().getChannel(channelName, false).publish(getClient(), members.keySet(), messageId);
        }
    }

    public void privateChat(Client source, String channel, Map<String, Object> data, String messageId)
    {
        String roomName = (String)data.get("room");
        Map<String, String> membersMap = _members.get(roomName);
        String[] peerNames = ((String)data.get("peer")).split(",");
        ArrayList<Client> to = new ArrayList<Client>(peerNames.length);
        
        for (String peerName : peerNames)
        {
            String peerId = membersMap.get(peerName);
            if (peerId!=null)
            {
                Client peer = getBayeux().getClient(peerId);
                if (peer!=null)
                    to.add(peer);
            }
        }

        if (to.size()>0)
        {
            {
                Map<String, Object> message = new HashMap<String, Object>();
                message.put("chat", data.get("chat"));
                message.put("user", data.get("user"));
                message.put("scope", "private");
                ((ChannelImpl)(getBayeux().getChannel(roomName,false))).deliver(getClient(),to,message,messageId);
                source.deliver(getClient(), roomName, message, messageId);
            }
        }
        else if (!"silent".equals((String)data.get("peer")))
        {
            Map<String, Object> message = new HashMap<String, Object>();
            message.put("chat", "Unknown user(s): "+data.get("peer"));
            message.put("user", "SERVER");
            message.put("scope", "error");
            source.deliver(source, roomName, message, messageId);
        }
    }
}
