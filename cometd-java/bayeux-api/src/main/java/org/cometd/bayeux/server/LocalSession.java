package org.cometd.bayeux.server;

import org.cometd.bayeux.client.ClientSession;



/* ------------------------------------------------------------ */
/**
 * <p>A LocalSession is a ClientSession within the server.
 * Unlike a ServerSession, a LocalSession may subscribe to channels
 * rather than just listen to them.
 * <p>
 * A LocalSession has an associated ServerSession and both share
 * the same ID, but have distinct sets of listeners and batching 
 * state etc.
 */
public interface LocalSession extends ClientSession
{
    ServerSession getServerSession();
    
    /* ------------------------------------------------------------ */
    /** Handshake the session.
     * This method is equivalent to calling {@link #handshake(boolean)}
     * passing false and ignoring exceptions.
     */
    void handshake();
    
}
