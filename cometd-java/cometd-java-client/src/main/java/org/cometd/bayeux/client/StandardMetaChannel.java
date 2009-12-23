package org.cometd.bayeux.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.MetaChannelType;
import org.cometd.bayeux.MetaMessage;

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
