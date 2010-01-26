package org.cometd.bayeux.client;


import java.io.IOException;

import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.client.BayeuxClient.Extension;



/**
 * @version $Revision$ $Date: 2009-12-08 09:42:45 +1100 (Tue, 08 Dec 2009) $
 */
public interface ClientSession extends Session
{
    /* ------------------------------------------------------------ */
    /** Add and extension to this session.
     * @param extension
     */
    void addExtension(Extension extension);

    
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
    

    /* ------------------------------------------------------------ */
    /**
     * @param channelName
     * @return
     */
    SessionChannel getChannel(String channelName);

    
}
