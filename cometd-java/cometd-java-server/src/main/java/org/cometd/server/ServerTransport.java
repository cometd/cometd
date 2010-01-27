package org.cometd.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.Transport;
import org.eclipse.jetty.util.ajax.JSON;

public class ServerTransport implements Transport
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

    private final String _name;
    private final Map<String,Object> _options;
    protected final List<String> _prefix=new ArrayList<String>();
    

    /* ------------------------------------------------------------ */
    protected ServerTransport(BayeuxServerImpl bayeux, String name,Map<String,Object> options)
    {
        _name=name;
        _options=options;
        _bayeux=bayeux;

        setOption(TIMEOUT_OPTION,_timeout);
        setOption(INTERVAL_OPTION,_interval);
        setOption(MAX_INTERVAL_OPTION,_maxInterval);
        setOption(MAX_LAZY_OPTION,_maxLazyTimeout);
        setOption(META_CONNECT_DELIVERY_OPTION,_metaConnectDeliveryOnly);
    }

    /* ------------------------------------------------------------ */
    public Object getAdvice()
    {
        return _advice;
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
    /** Get the max time before dispatching lazy message.
     * @return the max lazy timeout in MS
     */
    public long getMaxLazyTimeout()
    {
        return _maxLazyTimeout;
    }
    
    public String getName()
    {
        return _name;
    }
    
    public Object getOption(String name)
    {
        Object value = _options.get(name);
        
        String prefix=null;
        for (String segment:_prefix)
        {
            prefix = prefix==null?segment:(prefix+"."+segment);
            String key=prefix+"."+name;
            if (_options.containsKey(key))
                value=_options.get(key);
        }
        return value;
    }
    
    public boolean getOption(String option, boolean dftValue)
    {
        Object value = getOption(option);
        if (value==null)
            return false;
        if (value instanceof Boolean)
            return ((Boolean)value).booleanValue();
        return Boolean.parseBoolean(value.toString());
    }
    
    public int getOption(String option, int dftValue)
    {
        Object value = getOption(option);
        if (value==null)
            return -1;
        if (value instanceof Number)
            return ((Number)value).intValue();
        return Integer.parseInt(value.toString());
    }

    public long getOption(String option, long dftValue)
    {
        Object value = getOption(option);
        if (value==null)
            return -1;
        if (value instanceof Number)
            return ((Number)value).longValue();
        return Long.parseLong(value.toString());
    }
    public String getOption(String option, String dftValue)
    {
        Object value = getOption(option);
        return (value==null)?dftValue:value.toString();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.common.AbstractTransport#getOptionNames()
     */
    public Set<String> getOptionNames()
    {
        Set<String> names = new HashSet<String>();
        names.add(INTERVAL_OPTION);
        names.add(MAX_INTERVAL_OPTION);
        names.add(TIMEOUT_OPTION);
        names.add(MAX_LAZY_OPTION);
        return names;
    }

    public String getOptionPrefix()
    {
        String prefix=null;
        for (String segment:_prefix)
            prefix = prefix==null?segment:(prefix+"."+segment);
        return prefix;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the timeout.
     * @return the timeout
     */
    public long getTimeout()
    {
        return _timeout;
    }

    public boolean isMetaConnectDeliveryOnly()
    {
        return _metaConnectDeliveryOnly;
    }

    /* ------------------------------------------------------------ */
    public void setMetaConnectDeliveryOnly(boolean meta)
    {
        _metaConnectDeliveryOnly=meta;
    }
    /* ------------------------------------------------------------ */

    public void setOption(String name, Object value)
    {
        String prefix=getOptionPrefix();
        _options.put(prefix==null?name:(prefix+"."+name),value);
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
    public interface Dispatcher
    {
        void cancelDispatch();
        void dispatch();
    }
}
