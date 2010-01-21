package org.cometd.server;

import java.util.Map;
import java.util.Set;

import org.cometd.common.AbstractTransport;
import org.eclipse.jetty.util.ajax.JSON;

public class ServerTransport extends AbstractTransport
{
    public final static String TIMEOUT_OPTION="timeout";
    public final static String INTERVAL_OPTION="interval";
    public final static String MAX_INTERVAL_OPTION="maxInterval";
    public final static String MAX_LAZY_OPTION="maxLazyTimeout";
    public final static String META_CONNECT_DELIVERY_OPTION="metaConnectDeliverOnly";
    
    final protected BayeuxServerImpl _bayeux;
    protected long _interval=0;
    protected long _maxInterval=10000;
    protected long _timeout=10000;
    protected long _maxLazyTimeout=5000;
    protected boolean _metaConnectDeliveryOnly=false;
    protected Object _advice;

    /* ------------------------------------------------------------ */
    protected ServerTransport(BayeuxServerImpl bayeux, String name,Map<String,Object> options)
    {
        super(name,options);
        _bayeux=bayeux;

        setOption(TIMEOUT_OPTION,_timeout);
        setOption(INTERVAL_OPTION,_interval);
        setOption(MAX_INTERVAL_OPTION,_maxInterval);
        setOption(MAX_LAZY_OPTION,_maxLazyTimeout);
        setOption(META_CONNECT_DELIVERY_OPTION,_metaConnectDeliveryOnly);
    }

    /* ------------------------------------------------------------ */
    public interface Dispatcher
    {
        void dispatch();
        void cancelDispatch();
    }
    /* ------------------------------------------------------------ */
    /** Initialise the transport.
     * Initialise the transport, resolving default and direct options.
     * After the call to init, the {@link #getMutableOptions()} set should
     * be reset to reflect only the options that can be changed on a running
     * transport.
     * This implementation clears the mutable options set.
     */
    protected void init()
    {
        _interval=getOption(INTERVAL_OPTION,_interval);
        _maxInterval=getOption(MAX_INTERVAL_OPTION,_maxInterval);
        _timeout=getOption(TIMEOUT_OPTION,_timeout);
        _maxLazyTimeout=getOption(MAX_LAZY_OPTION,_maxLazyTimeout);
        _metaConnectDeliveryOnly=getOption(META_CONNECT_DELIVERY_OPTION,_metaConnectDeliveryOnly);

        _advice=new JSON.Literal("{\"reconnect\":\"retry\",\"interval\":" + _interval + ",\"timeout\":" + _timeout + "}");
    }

    /* ------------------------------------------------------------ */
    public void setMetaConnectDeliveryOnly(boolean meta)
    {
        _metaConnectDeliveryOnly=meta;
    }
    /* ------------------------------------------------------------ */

    public boolean isMetaConnectDeliveryOnly()
    {
        return _metaConnectDeliveryOnly;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.common.AbstractTransport#getOptionNames()
     */
    @Override
    public Set<String> getOptionNames()
    {
        Set<String> names = super.getOptionNames();
        names.add(INTERVAL_OPTION);
        names.add(MAX_INTERVAL_OPTION);
        names.add(TIMEOUT_OPTION);
        names.add(MAX_LAZY_OPTION);
        return names;
    }

    /* ------------------------------------------------------------ */
    /** Get the interval.
     * @return the interval
     */
    public long getInterval()
    {
        return _interval;
    }

    /* ------------------------------------------------------------ */
    /** Get the maxInterval.
     * @return the maxInterval
     */
    public long getMaxInterval()
    {
        return _maxInterval;
    }

    /* ------------------------------------------------------------ */
    /** Get the timeout.
     * @return the timeout
     */
    public long getTimeout()
    {
        return _timeout;
    }

    /* ------------------------------------------------------------ */
    /** Get the max time before dispatching lazy message.
     * @return the max lazy timeout in MS
     */
    public long getMaxLazyTimeout()
    {
        return _maxLazyTimeout;
    }
    
    /* ------------------------------------------------------------ */
    public Object getAdvice()
    {
        return _advice;
    }
}
