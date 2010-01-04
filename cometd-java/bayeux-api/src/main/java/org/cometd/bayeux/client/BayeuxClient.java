package org.cometd.bayeux.client;

import java.io.IOException;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Message;


/* ------------------------------------------------------------ */
/** A Bayeux Client interface.
 * <p>
 * This interface represents both the static state of the 
 * client via the {@link Bayeux} interface, and the dynamic 
 * state of the client via the {@link ClientSession} interface.
 * 
 */
public interface BayeuxClient extends Bayeux, ClientSession
{
    /* ------------------------------------------------------------ */
    /** Initiate a handshake with the server.
     * @param async true if the implementation should not wait for success or failure,
     * and a {@link MetaListener} can be used instead.
     * @throws IOException If the handshake is unsuccessful
     */
    void handshake(boolean async) throws IOException;
    
    boolean isHandshook();
    boolean isConnected();


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** All Bayeux Client Listeners are derived from this interface.
     */
    interface BayeuxClientListener extends Bayeux.BayeuxListener
    {};


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** Listener for all meta messages
     */
    public interface MetaListener extends BayeuxClientListener
    {
        void onMetaMessage(BayeuxClient client, Message message,boolean successful, String error);
    }
}
