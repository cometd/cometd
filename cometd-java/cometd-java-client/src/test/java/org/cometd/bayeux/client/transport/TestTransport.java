package org.cometd.bayeux.client.transport;

/**
 * @version $Revision$ $Date$
 */
public class TestTransport implements Transport
{
    public void send(Exchange exchange, boolean synchronous)
    {
        exchange.success(null);
    }

    public String getType()
    {
        return null;
    }

    public boolean accept(String bayeuxVersion)
    {
        return false;
    }
}
