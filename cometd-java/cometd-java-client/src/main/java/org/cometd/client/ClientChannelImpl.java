package org.cometd.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.client.ClientChannel;
import org.cometd.common.ChannelId;

public class ClientChannelImpl implements ClientChannel
{
    private final ChannelId _id;
    private final List<ClientChannelListener> _listeners = new CopyOnWriteArrayList<ClientChannelListener>();
    
    protected ClientChannelImpl(String channelId)
    {
        _id=new ChannelId(channelId);
    }

    @Override
    public void addListener(ClientChannelListener listener)
    {
        _listeners.add(listener);
    }

    @Override
    public void removeListener(ClientChannelListener listener)
    {
        _listeners.remove(listener);
    }

    @Override
    public String getId()
    {
        return _id.toString();
    }
    
    public ChannelId getChannelId()
    {
        return _id;
    }

    @Override
    public boolean isDeepWild()
    {
        return _id.isDeepWild();
    }

    @Override
    public boolean isMeta()
    {
        return _id.isMeta();
    }

    @Override
    public boolean isService()
    {
        return _id.isService();
    }

    @Override
    public boolean isWild()
    {
        return _id.isWild();
    }

}
