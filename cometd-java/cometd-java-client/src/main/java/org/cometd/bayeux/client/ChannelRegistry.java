package org.cometd.bayeux.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.StandardChannel;

/**
 * @version $Revision$ $Date$
 */
public class ChannelRegistry
{
    private final ConcurrentMap<String, Channel.Mutable> channels = new ConcurrentHashMap<String, Channel.Mutable>();
    private final IClientSession session;

    public ChannelRegistry(IClientSession session)
    {
        this.session = session;
    }

    public Channel.Mutable from(String channelName, boolean create)
    {
        Channel.Mutable channel = channels.get(channelName);
        if (channel == null && create)
        {
            channel = new StandardChannel(session, channelName);
            Channel.Mutable existing = channels.putIfAbsent(channelName, channel);
            if (existing != null)
                channel = existing;
        }
        return channel;
    }

    public void notifySubscribers(Channel.Mutable channel, Message message)
    {
        // TODO: notify also globbed channels
        channel.notifySubscribers(message);
    }
}
