package org.cometd.bayeux.server;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Message;

public interface BayeuxServer extends Bayeux
{
    /* ------------------------------------------------------------ */
    /** ServletContext attribute name used to obtain the Bayeux object */
    public static final String ATTRIBUTE ="org.cometd.bayeux";


    /* ------------------------------------------------------------ */
    /**
     * Adds the given extension to this bayeux object.
     * @param extension the extension to add
     * @see #removeExtension(Extension)
     */
    void addExtension(Extension extension);

    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    void addListener(BayeuxServerListener listener);
    
    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    void removeListener(BayeuxServerListener listener);

    /* ------------------------------------------------------------ */
    /**
     * @param channelId
     * @return
     */
    ServerChannel getChannel(String channelId);
    
    /* ------------------------------------------------------------ */
    /**
     * @param channelId
     * @param create
     * @return
     */
    ServerChannel getChannel(String channelId, boolean create);
    
    /* ------------------------------------------------------------ */
    /** Get a server session my ID
     * @param clientId the ID
     * @return the server session or null if no such valid session exists.
     */
    ServerSession getSession(String clientId);


    /* ------------------------------------------------------------ */
    /** Create a local session.
     * A Local session is a server-side ClientSession.  This allows the 
     * server to have special clients resident within the same JVM.
     * @param idHint A hint to be included in the unique client ID.
     * @return A new LocalSession
     */
    LocalSession newLocalSession(String idHint);
    

    /* ------------------------------------------------------------ */
    /** Create a new Message.
     * @return A new or recycled message instance.
     */
    ServerMessage.Mutable newMessage();
    

    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public SecurityPolicy getSecurityPolicy();

    /* ------------------------------------------------------------ */
    /**
     * @param securityPolicy
     */
    public void setSecurityPolicy(SecurityPolicy securityPolicy);


    /* ------------------------------------------------------------ */
    /**
     * Get the current transport for the current thread.
     * A transport object will be: <ul>
     * <li>A javax.servlet.http.HttpServletRequest instance for a HTTP transport
     * <li>An org.eclipse.jetty.websocket.WebSocket instance for WebSocket transports
     * </ul>
     */
    public Object getCurrentTransport();

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    interface BayeuxServerListener extends BayeuxListener
    {}

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    public interface ChannelListener extends BayeuxServerListener
    {
        public void channelAdded(ServerChannel channel);
        public void channelRemoved(String channelId);
    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    public interface SessionListener extends BayeuxServerListener
    {
        public void sessionAdded(ServerSession session);
        public void sessionRemoved(ServerSession session,boolean timedout);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public interface SubscriptionListener extends BayeuxServerListener
    {
        public void subscribed(ServerSession session, ServerChannel channel);
        public void unsubscribed(ServerSession session, ServerChannel channel);
    }
    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * <p>Extension API for bayeux server.</p>
     * <p>Implementations of this interface allow to modify incoming and outgoing messages
     * respectively just before and just after they are handled by the implementation,
     * either on client side or server side.</p>
     * <p>Extensions are be registered in order and one extension may allow subsequent
     * extensions to process the message by returning true from the callback method, or
     * forbid further processing by returning false.</p>
     *
     * @see BayeuxServer#addExtension(Extension)
     */
    public interface Extension
    {
        /**
         * Callback method invoked every time a normal message is incoming.
         * @param from the session that sent the message
         * @param message the incoming message
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcv(ServerSession from, Message.Mutable message);

        /**
         * Callback method invoked every time a meta message is incoming.
         * @param from the session that sent the message
         * @param message the incoming meta message
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcvMeta(ServerSession from, Message.Mutable message);

        /**
         * Callback method invoked every time a normal message is outgoing.
         * @param to the session receiving the message
         * @param message the outgoing message
         * @return true if message processing should continue, false if it should stop
         */
        boolean send(ServerSession to, Message.Mutable message);

        /**
         * Callback method invoked every time a meta message is outgoing.
         * @param to the session receiving the message
         * @param message the outgoing meta message
         * @return true if message processing should continue, false if it should stop
         */
        boolean sendMeta(ServerSession to, Message.Mutable message);
    }
}
