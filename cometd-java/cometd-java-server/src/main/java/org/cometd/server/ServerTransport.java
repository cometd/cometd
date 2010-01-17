package org.cometd.server;

import org.cometd.common.AbstractTransport;
import org.cometd.server.transports.DefaultTransport;
import org.eclipse.jetty.util.ajax.JSON;

public class ServerTransport extends AbstractTransport
{
    final protected BayeuxServerImpl _bayeux;
    final protected DefaultTransport _defaultTransport;
    protected long _interval=0;
    protected long _maxInterval=10000;
    protected long _timeout=10000;
    protected Object _advice;

    /* ------------------------------------------------------------ */
    protected ServerTransport(BayeuxServerImpl bayeux, DefaultTransport dftTransport, String name)
    {
        super(name);
        _bayeux=bayeux;
        _defaultTransport=dftTransport;
    }

    /* ------------------------------------------------------------ */
    public interface Dispatcher
    {
        void dispatch();
        void cancel();
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
        _mutable.clear();
        _interval=getOption(DefaultTransport.INTERVAL_OPTION,_interval);
        _maxInterval=getOption(DefaultTransport.INTERVAL_OPTION,_maxInterval);
        _timeout=getOption(DefaultTransport.TIMEOUT_OPTION,_timeout);

        _advice=new JSON.Literal("{\"reconnect\":\"retry\",\"interval\":" + _interval + ",\"timeout\":" + _timeout + "}");
    }
    


    /* ------------------------------------------------------------ */
    /** Get an option with default resolution.
     * <p>
     * Get an option from this transports options, else from the default
     * transport passed to {@link #init(DefaultTransport)} else from the 
     * dftValue argument passed.
     * @param option
     * @param dftValue
     * @return The option value as a string.
     */
    public String getOption(String option, String dftValue)
    {
        Object value = getOptions().get(option);
        if (value==null && _defaultTransport!=null)
            value = _defaultTransport.getOptions().get(option);
        
        return (value==null)?dftValue:value.toString();
    }
    /* ------------------------------------------------------------ */
    /** Get an option with default resolution.
     * <p>
     * Get an option from this transports options, else from the default
     * transport passed to {@link #init(DefaultTransport)} else from the 
     * dftValue argument passed.
     * @param option
     * @param dftValue
     * @return The option value as a long.
     */
    public long getOption(String option, long dftValue)
    {
        Object value = getOptions().get(option);
        if (value==null && _defaultTransport!=null)
            value = _defaultTransport.getOptions().get(option);
        
        return (value==null)?dftValue:Long.parseLong(value.toString());
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
    public Object getAdvice()
    {
        return _advice;
    }
}
