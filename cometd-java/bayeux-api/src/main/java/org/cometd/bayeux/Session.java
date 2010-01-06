package org.cometd.bayeux;


/* ------------------------------------------------------------ */
/** A Bayeux Session
 * <p>A bayeux session represents a connection between a bayeux client and 
 * a bayeux server.  Previously this interface was called "Client", but this
 * resulted in confusion between the various representation of the server-side 
 * elements of a "Client".   Thus this entity has been renamed "Session", but
 * for backwards compatibility with the wire protocol, it is identified by 
 * a clientID string.
 */
public interface Session
{
    /* ------------------------------------------------------------ */
    /** The ClientId of the session.
     * <p>This would more correctly be called a "sessionId", but for
     * backwards compatibility with the bayeux protocol, it is a clientId
     * that identifies a session.
     * @return A string identifying the current session.
     */
    String getId();

    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    void addListener(SessionListener listener);

    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    void removeListener(SessionListener listener);


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    interface SessionListener extends Bayeux.BayeuxListener
    {};


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    public interface MessageListener extends SessionListener
    {
        void onMessage(Session session, Message message);
    }
}
