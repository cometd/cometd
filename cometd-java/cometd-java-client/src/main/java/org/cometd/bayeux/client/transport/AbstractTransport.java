package org.cometd.bayeux.client.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.CommonMessage;
import org.cometd.bayeux.IMessage;
import org.cometd.bayeux.StandardMessage;
import org.eclipse.jetty.util.ajax.JSON;

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

    protected void notifyMessages(List<CommonMessage.Mutable> messages)
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

    public IMessage.Mutable newMessage()
    {
        return newMessage(null);
    }

    public IMessage.Mutable newMessage(Map<String, Object> fields)
    {
        return new StandardMessage(fields);
    }

    @Override
    public String toString()
    {
        return getType();
    }

    protected List<CommonMessage.Mutable> toMessages(String content)
    {
        // TODO: this must be improved
        List<CommonMessage.Mutable> result = new ArrayList<CommonMessage.Mutable>();
        Object object = JSON.parse(content);
        if (object instanceof Map)
        {
            Map<String, Object> map = (Map<String, Object>)object;
            result.add(newMessage(map));
        }
        else if (object instanceof Object[])
        {
            Object[] objects = (Object[])object;
            for (Object obj : objects)
            {
                Map<String, Object> map = (Map<String, Object>)obj;
                result.add(newMessage(map));
            }
        }
        return result;
    }
}
