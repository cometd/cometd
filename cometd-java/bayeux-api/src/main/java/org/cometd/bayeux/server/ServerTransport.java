package org.cometd.bayeux.server;

import java.net.InetSocketAddress;

import org.cometd.bayeux.Transport;

public interface ServerTransport extends Transport
{

    public Object getAdvice();

    /** Get the timeout.
     * @return the timeout
     */
    public long getTimeout();
    
    /** Get the interval.
     * @return the interval
     */
    public long getInterval();

    /** Get the maxInterval.
     * @return the maxInterval
     */
    public long getMaxInterval();

    /** Get the max time before dispatching lazy message.
     * @return the max lazy timeout in MS
     */
    public long getMaxLazyTimeout();

    public boolean isMetaConnectDeliveryOnly();
    
    public InetSocketAddress getCurrentRemoteAddress();
    
    public InetSocketAddress getCurrentLocalAddress();

}
