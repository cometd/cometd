package org.cometd.client.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.BayeuxClient;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision: 902 $ $Date$
 */
public abstract class AbstractTransport implements ClientTransport
{
    protected BayeuxClient _bayeux;
    private final List<TransportListener> listeners = new ArrayList<TransportListener>();

    @Override
    public void init(BayeuxClient bayeux)
    {
        _bayeux=bayeux;
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
        return getName();
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

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Transport#getName()
     */
    @Override
    public String getName()
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Transport#getOption(java.lang.String)
     */
    @Override
    public Object getOption(String name)
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Transport#getOptionNames()
     */
    @Override
    public Set<String> getOptionNames()
    {
        // TODO Auto-generated method stub
        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Transport#getOptionPrefix()
     */
    @Override
    public String getOptionPrefix()
    {
        // TODO Auto-generated method stub
        return null;
    }
    
    
    
}
