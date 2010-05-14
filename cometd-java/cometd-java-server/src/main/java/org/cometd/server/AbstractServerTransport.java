package org.cometd.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.Transport;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerTransport;
import org.eclipse.jetty.util.ajax.JSON;


/* ------------------------------------------------------------ */
/** The base class of all server transports.
 * 
 */
public abstract class AbstractServerTransport implements ServerTransport
{
    public final static String TIMEOUT_OPTION="timeout";
    public final static String INTERVAL_OPTION="interval";
    public final static String MAX_INTERVAL_OPTION="maxInterval";
    public final static String MAX_LAZY_OPTION="maxLazyTimeout";
    public final static String META_CONNECT_DELIVERY_OPTION="metaConnectDeliverOnly";
    
    final protected BayeuxServerImpl _bayeux;
    public long _interval=0;
    public long _maxInterval=10000;
    protected long _timeout=10000;
    public long _maxLazyTimeout=5000;
    public boolean _metaConnectDeliveryOnly=false;
    public Object _advice;

    private final String _name;
    private final Map<String,Object> _options;
    private final List<String> _prefix=new ArrayList<String>();
    

    /* ------------------------------------------------------------ */
    /** Construct a ServerTransport.
     * </p>
     * <p>The construct is passed the {@link BayeuxServerImpl} instance for
     * the transport.  The {@link BayeuxServerImpl#getOptions()} map is
     * populated with the default options known by this transport. The options
     * are then inspected again when {@link #init()} is called, to set the 
     * actual values used.  The options are arranged into a naming hierarchy
     * by derived classes adding prefix segments by calling add {@link #addPrefix(String)}.
     * Calls to {@link #getOption(String)} will use the list of prefixes
     * to search for the most specific option set.
     * </p>
     * <p>
     */
    protected AbstractServerTransport(BayeuxServerImpl bayeux, String name)
    {
        _name=name;
        _options=bayeux.getOptions();
        _bayeux=bayeux;

        setOption(TIMEOUT_OPTION,_timeout);
        setOption(INTERVAL_OPTION,_interval);
        setOption(MAX_INTERVAL_OPTION,_maxInterval);
        setOption(MAX_LAZY_OPTION,_maxLazyTimeout);
        setOption(META_CONNECT_DELIVERY_OPTION,_metaConnectDeliveryOnly);
    }

    /* ------------------------------------------------------------ */
    /** Add an option name prefix segment.
     * <p> Normally this is called by the super class constructors to establish 
     * a naming hierarchy for options and iteracts with the {@link #setOption(String, Object)}
     * method to create a naming hierarchy for options.
     * For example the following sequence of calls:<pre>
     *   setOption("foo","x");
     *   setOption("bar","y");
     *   addPrefix("long-polling");
     *   setOption("foo","z");
     *   setOption("whiz","p");
     *   addPrefix("jsonp");
     *   setOption("bang","q");
     *   setOption("bar","r");
     * </pre>
     * will establish the following option names and values:<pre>
     *   foo: x
     *   bar: y
     *   long-polling.foo: z
     *   long-polling.whiz: p
     *   long-polling.jsonp.bang: q
     *   long-polling.jsonp.bar: r
     * </pre>
     * The various {@link #getOption(String)} methods will search this
     * name tree for the most specific match.
     * 
     * @param segment name
     */
    protected void addPrefix(String segment)
    {
        _prefix.add(segment);
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
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.Transport#getName()
     */
    public String getName()
    {
        return _name;
    }
    
    /* ------------------------------------------------------------ */
    /** Get an option value.
     * Get an option value by searching the option name tree.  The option
     * map obtained by calling {@link BayeuxServerImpl#getOptions()} is
     * searched for the option name with the most specific prefix.
     * If this transport was initialised with calls: <pre>
     *   addPrefix("long-polling");
     *   addPrefix("jsonp");
     * </pre>
     * then a call to getOption("foobar") will look for the 
     * most specific value with names:<pre>
     *   long-polling.json.foobar
     *   long-polling.foobar
     *   foobar
     * </pre>
     */
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
    
    /* ------------------------------------------------------------ */
    /** Get option or default value.
     * @see #getOption(String)
     * @param option The option name.
     * @param dftValue The default value.
     * @return option or default value
     */
    public boolean getOption(String option, boolean dftValue)
    {
        Object value = getOption(option);
        if (value==null)
            return false;
        if (value instanceof Boolean)
            return ((Boolean)value).booleanValue();
        return Boolean.parseBoolean(value.toString());
    }
    
    /* ------------------------------------------------------------ */
    /** Get option or default value.
     * @see #getOption(String)
     * @param option The option name.
     * @param dftValue The default value.
     * @return option or default value
     */
    public int getOption(String option, int dftValue)
    {
        Object value = getOption(option);
        if (value==null)
            return -1;
        if (value instanceof Number)
            return ((Number)value).intValue();
        return Integer.parseInt(value.toString());
    }

    /* ------------------------------------------------------------ */
    /** Get option or default value.
     * @see #getOption(String)
     * @param option The option name.
     * @param dftValue The default value.
     * @return option or default value
     */
    public long getOption(String option, long dftValue)
    {
        Object value = getOption(option);
        if (value==null)
            return -1;
        if (value instanceof Number)
            return ((Number)value).longValue();
        return Long.parseLong(value.toString());
    }
    
    /* ------------------------------------------------------------ */
    /** Get option or default value.
     * @see #getOption(String)
     * @param option The option name.
     * @param dftValue The default value.
     * @return option or default value
     */
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

    /* ------------------------------------------------------------ */
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
