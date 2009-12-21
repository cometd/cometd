package org.cometd.bayeux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.client.MetaChannel;
import org.cometd.bayeux.client.MetaChannelType;
import org.cometd.bayeux.client.MetaMessage;
import org.cometd.bayeux.client.MetaMessageListener;

/**
 * @version $Revision$ $Date$
 */
public class StandardMetaChannel implements MetaChannel.Mutable
{
    private final List<MetaMessageListener> listeners = new CopyOnWriteArrayList<MetaMessageListener>();
    private final MetaChannelType type;

    public StandardMetaChannel(MetaChannelType type)
    {
        this.type = type;
    }

    public MetaChannelType getType()
    {
        return type;
    }

    public void subscribe(final MetaMessageListener listener)
    {
        listeners.add(listener);
    }

    public void unsubscribe(MetaMessageListener listener)
    {
        listeners.remove(listener);
    }

    public void notifySubscribers(MetaMessage metaMessage)
    {
        for (MetaMessageListener listener : listeners)
            listener.onMetaMessage(metaMessage);
    }
}
