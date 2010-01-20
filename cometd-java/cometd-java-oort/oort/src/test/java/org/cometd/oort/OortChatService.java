/**
 *
 */
package org.cometd.oort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.ServletContext;

import org.cometd.Bayeux;
import org.cometd.Channel;
import org.cometd.Client;
import org.cometd.RemoveListener;
import org.cometd.server.BayeuxService;
import org.eclipse.jetty.util.log.Log;

public class OortChatService extends BayeuxService
{
    /**
     * A map(channel, map(userName, clientId))
     */
    private final ConcurrentMap<String, Set<String>> _members = new ConcurrentHashMap<String, Set<String>>();
    private Oort _oort;
    private Seti _seti;

    public OortChatService(ServletContext context)
    {
        super((Bayeux)context.getAttribute(Bayeux.ATTRIBUTE), "chat");

        _oort = (Oort)context.getAttribute(Oort.OORT_ATTRIBUTE);
        if (_oort==null)
            throw new RuntimeException("!"+Oort.OORT_ATTRIBUTE);
        _seti = (Seti)context.getAttribute(Seti.SETI_ATTRIBUTE);
        if (_seti==null)
            throw new RuntimeException("!"+Seti.SETI_ATTRIBUTE);

        _oort.observeChannel("/chat/**");
        subscribe("/chat/**", "trackMembers");
        subscribe("/service/privatechat", "privateChat");
    }

    public void trackMembers(final Client joiner, final String channelName, Object data, final String messageId)
    {
        if (data instanceof Object[])
        {
            Set<String> members = _members.get(channelName);
            if (members == null)
            {
                Set<String> newMembers = new HashSet<String>();
                members = _members.putIfAbsent(channelName, newMembers);
                if (members == null) members = newMembers;
            }
            boolean added=false;
            for (Object user : (Object[])data)
                added|=members.add(user.toString());
            if (added)
            {
                Log.info("Members: " + members);
                // Broadcast the members to all existing members
                getBayeux().getChannel(channelName, false).publish(getClient(), members, messageId);
            }
        }
        else if (data instanceof Map)
        {
            Map<String, Object> map = (Map<String, Object>) data;

            if (Boolean.TRUE.equals(map.get("join")))
            {

                Set<String> members = _members.get(channelName);
                if (members == null)
                {
                    Set<String> newMembers = new HashSet<String>();
                    members = _members.putIfAbsent(channelName, newMembers);
                    if (members == null) members = newMembers;
                }

                final String userName = (String)map.get("user");

                members.add(userName);

                if (!_oort.isOort(joiner))
                    _seti.associate(userName,joiner);

                joiner.addListener(new RemoveListener(){

                    public void removed(String clientId, boolean timeout)
                    {
                        if (!_oort.isOort(joiner))
                            _seti.disassociate(userName);
                        if (timeout)
                        {
                            Channel channel=getBayeux().getChannel(channelName,false);
                            if (channel!=null)
                            {
                                Map<String,Object> leave = new HashMap<String,Object>();
                                leave.put("leave",Boolean.TRUE);
                                leave.put("user",userName);
                                channel.publish(null,leave,null);
                            }
                        }
                    }

                });

                Log.info("Members: " + members);
                // Broadcast the members to all existing members
                getBayeux().getChannel(channelName, false).publish(getClient(), members, messageId);

            }

            if (Boolean.TRUE.equals(map.get("leave")))
            {
                Set<String> members = _members.get(channelName);
                if (members == null)
                {
                    Set<String> newMembers = new HashSet<String>();
                    members = _members.putIfAbsent(channelName, newMembers);
                    if (members == null) members = newMembers;
                }

                String userName = (String)map.get("user");
                members.remove(userName);

                Log.info("Members: " + members);
                // Broadcast the members to all existing members
                getBayeux().getChannel(channelName, true).publish(getClient(), members, messageId);
            }
        }
    }

    public void privateChat(Client source, String channel, Map<String, Object> data, String messageId)
    {
        String toUid=(String)data.get("peer");
        String toChannel=(String)data.get("room");
        source.deliver(source,toChannel,data,messageId);
        _seti.sendMessage(toUid,toChannel,data);
    }
}

