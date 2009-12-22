package org.cometd.bayeux.client.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.client.BayeuxMetaMessage;
import org.cometd.bayeux.client.MetaMessage;

/**
 * @version $Revision$ $Date$
 */
public abstract class AbstractTransport implements Transport
{
    private final List<TransportListener> listeners = new ArrayList<TransportListener>();

    public void init()
    {
    }

    public void destroy()
    {
    }

    public void addListener(TransportListener listener)
    {
        listeners.add(listener);
    }

    public void removeListener(TransportListener listener)
    {
        listeners.remove(listener);
    }

    protected void notifyMetaMessages(MetaMessage.Mutable... metaMessages)
    {
        for (TransportListener listener : listeners)
            listener.onMetaMessages(metaMessages);
    }

    protected void notifyConnectException(Throwable x)
    {
        for (TransportListener listener : listeners)
            listener.onConnectException(x);
    }

    protected void notifyException(Throwable x)
    {
        for (TransportListener listener : listeners)
            listener.onException(x);
    }

    protected void notifyExpire()
    {
        for (TransportListener listener : listeners)
            listener.onExpire();
    }

    protected void notifyProtocolError()
    {
        for (TransportListener listener : listeners)
            listener.onProtocolError();
    }

    public MetaMessage.Mutable newMetaMessage(Map<String, Object> fields)
    {
        return fields == null ? new BayeuxMetaMessage() : new BayeuxMetaMessage(fields);
    }

    @Override
    public String toString()
    {
        return getType();
    }
}
