package org.cometd.client.transport;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.client.BayeuxClient;
import org.cometd.common.AbstractTransport;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * @version $Revision: 902 $ $Date$
 */
public abstract class ClientTransport extends AbstractTransport
{
    public final static String TIMEOUT_OPTION = "timeout";
    public final static String INTERVAL_OPTION = "interval";
    public final static String MAX_NETWORK_DELAY_OPTION = "maxNetworkDelay";

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
    public void init(BayeuxClient bayeux, HttpURI uri)
    {
        _timeout=getOption(TIMEOUT_OPTION,_timeout);
        _interval=getOption(INTERVAL_OPTION,_interval);
        _maxNetworkDelay=getOption(MAX_NETWORK_DELAY_OPTION,_maxNetworkDelay);
    }

    /* ------------------------------------------------------------ */
    public abstract void reset();

    /* ------------------------------------------------------------ */
    public abstract boolean accept(String version);

    /* ------------------------------------------------------------ */
    public abstract void send(TransportListener listener, Message.Mutable... messages);

    /* ------------------------------------------------------------ */
    public Message.Mutable newMessage()
    {
        return new HashMapMessage();
    }

    /* ------------------------------------------------------------ */
    protected List<Message.Mutable> toMessages(String content)
    {
        Object object = _batchJSON.parse(new JSON.StringSource(content));
        if (object instanceof Message.Mutable)
            return Collections.singletonList((Message.Mutable)object);
        return Arrays.asList((Message.Mutable[])object);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private JSON _json=new JSON()
    {
        @Override
        protected Map newMap()
        {
            return new HashMap<String, Object>();
        }

    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private JSON _msgJSON=new JSON()
    {
        @Override
        protected Map newMap()
        {
            return new HashMapMessage();
        }

        @Override
        protected JSON contextFor(String field)
        {
            return _json;
        }
    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private JSON _batchJSON=new JSON()
    {
        @Override
        protected Map newMap()
        {
            return new HashMapMessage();
        }

        @Override
        protected Object[] newArray(int size)
        {
            return new Message.Mutable[size];
        }

        @Override
        protected JSON contextFor(String field)
        {
            return _json;
        }

        @Override
        protected JSON contextForArray()
        {
            return _msgJSON;
        }
    };
}
