package org.cometd.client.transport;

import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.common.AbstractTransport;
import org.cometd.common.HashMapMessage;

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

    public void init()
    {
        _timeout=getOption(TIMEOUT_OPTION,_timeout);
        _interval=getOption(INTERVAL_OPTION,_interval);
        _maxNetworkDelay=getOption(MAX_NETWORK_DELAY_OPTION,_maxNetworkDelay);
    }

    public abstract void abort();

    /* ------------------------------------------------------------ */
    public abstract void reset();

    /* ------------------------------------------------------------ */
    public abstract boolean accept(String version);

    /* ------------------------------------------------------------ */
    public abstract void send(TransportListener listener, Message.Mutable... messages);

    /* ------------------------------------------------------------ */
    protected List<Message.Mutable> parseMessages(String content)
    {
        return HashMapMessage.parseMessages(content);
    }
}
