package org.cometd.bayeux.client.transport;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketTransport implements Transport
{
    private volatile boolean accept = true;

     public String getType()
    {
        return "websocket";
    }

    public boolean accept(String bayeuxVersion)
    {
        return accept;
    }

    public void send(Exchange exchange, boolean synchronous)
    {
        
    }
}
