package org.cometd.client.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.Message;
import org.cometd.client.BayeuxClient;
import org.cometd.common.AbstractTransport;
import org.cometd.common.HashMapMessage;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision: 902 $ $Date$
 */
public abstract class ClientTransport extends AbstractTransport
{
    public final static String TIMEOUT_OPTION="timeout";
    public final static String INTERVAL_OPTION="interval";
    public final static String MAX_NETWORK_DELAY_OPTION="maxNetworkDelay";
    
    protected BayeuxClient _bayeux;
    protected HttpURI _uri;

    private TransportListener _listener;
    
    protected long _timeout=-1;
    protected long _interval=-1;
    protected long _maxNetworkDelay=10000;

    /* ------------------------------------------------------------ */
    protected ClientTransport(String name,Map<String,Object> options)
    {
        super(name,options);
        
        setOption(TIMEOUT_OPTION,_timeout);
        setOption(INTERVAL_OPTION,_interval);
        setOption(MAX_NETWORK_DELAY_OPTION,_maxNetworkDelay);
    }
    
    /* ------------------------------------------------------------ */
    public void init(BayeuxClient bayeux, HttpURI uri, TransportListener listener)
    {
        _bayeux=bayeux;
        _uri=uri;
        _listener=listener;
        
        _timeout=getOption(TIMEOUT_OPTION,_timeout);
        _interval=getOption(INTERVAL_OPTION,_interval);
        _maxNetworkDelay=getOption(MAX_NETWORK_DELAY_OPTION,_maxNetworkDelay);
    }

    /* ------------------------------------------------------------ */
    public abstract void reset();
    
    /* ------------------------------------------------------------ */
    public abstract boolean accept(String version);
    
    /* ------------------------------------------------------------ */
    public abstract void send(Message... messages);
    
    /* ------------------------------------------------------------ */
    protected void notifyMessages(List<Message.Mutable> messages)
    {
        _listener.onMessages(messages);
    }

    /* ------------------------------------------------------------ */
    protected void notifyConnectException(Throwable x)
    {
        _listener.onConnectException(x);
    }

    /* ------------------------------------------------------------ */
    protected void notifyException(Throwable x)
    {
        _listener.onException(x);
    }

    /* ------------------------------------------------------------ */
    protected void notifyExpire()
    {
        _listener.onExpire();
    }

    /* ------------------------------------------------------------ */
    protected void notifyProtocolError()
    {
        _listener.onProtocolError();
    }

    /* ------------------------------------------------------------ */
    public Message.Mutable newMessage()
    {
        return new HashMapMessage();
    }

    /* ------------------------------------------------------------ */
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
