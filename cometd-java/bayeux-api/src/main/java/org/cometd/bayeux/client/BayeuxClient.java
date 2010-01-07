package org.cometd.bayeux.client;

import java.io.IOException;

import org.cometd.bayeux.Bayeux;


/**
 * The Bayeux client interface represents both the static state of the
 * client via the {@link Bayeux} interface, and the dynamic
 * state of the client via the {@link ClientSession} interface.
 */
public interface BayeuxClient extends Bayeux, ClientSession
{
    /**
     * <p>Initiates the bayeux protocol handshake with the server.</p>
     * <p>The handshake can be synchronous or asynchronous. <br/>
     * A synchronous handshake will wait for the server's response (or lack thereof) before returning
     * to the caller. <br/>
     * An asynchronous handshake will not wait for the server and the caller may be notified via a
     * {@link MetaMessageListener}.</p>
     *
     * @param async true if the handshake must be asynchronous, false otherwise.
     * @throws IOException if a synchronous handshake fails
     */
    void handshake(boolean async) throws IOException;

    /** All Bayeux Client Listeners are derived from this interface.
     */
    interface BayeuxClientListener extends Bayeux.BayeuxListener
    {
    }
}
