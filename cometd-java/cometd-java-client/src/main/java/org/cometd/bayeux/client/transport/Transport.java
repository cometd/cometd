package org.cometd.bayeux.client.transport;

/**
 * @version $Revision$ $Date$
 */
public interface Transport
{
    void send(Exchange exchange, boolean synchronous);

    String getType();

    boolean accept(String bayeuxVersion);
}
