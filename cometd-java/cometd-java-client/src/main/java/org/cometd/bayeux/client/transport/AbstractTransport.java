package org.cometd.bayeux.client.transport;

import java.util.ArrayList;
import java.util.List;

import org.cometd.bayeux.Message;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision: 902 $ $Date$
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

    protected void notifyMessages(List<Message.Mutable> messages)
    {
        for (TransportListener listener : listeners)
            listener.onMessages(messages);
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

    public Message.Mutable newMessage()
    {
        return new HashMapMessage();
    }

    @Override
    public String toString()
    {
        return getType();
    }

    protected List<Message.Mutable> toMessages(String content)
    {
        List<Message.Mutable> result = new ArrayList<Message.Mutable>();
        Object object = JSON.parse(content);
        if (object instanceof Message.Mutable)
        {
            result.add((Message.Mutable)object);
        }
        else if (object instanceof Object[])
        {
            Object[] objects = (Object[])object;
            for (Object obj : objects)
            {
                if (obj instanceof Message.Mutable)
                {
                    result.add((Message.Mutable)obj);
                }
            }
        }
        return result;
    }
}
