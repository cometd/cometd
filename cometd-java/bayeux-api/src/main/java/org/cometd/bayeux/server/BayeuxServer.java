package org.cometd.bayeux.server;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.ServerChannel.ServerChannelListener;

public interface BayeuxServer extends Bayeux
{
    /** ServletContext attribute name used to obtain the Bayeux object */
    public static final String ATTRIBUTE ="org.cometd.bayeux";

    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    public SecurityPolicy getSecurityPolicy();

    /* ------------------------------------------------------------ */
    /**
     * @param channelId
     * @param create
     * @return
     */
    ServerChannel getServerChannel(String channelId, boolean create);
    
    /* ------------------------------------------------------------ */
    /** Get a server session my ID
     * @param clientId the ID
     * @return the server session or null if no such valid session exists.
     */
    ServerSession getServerSession(String clientId);


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
    ServerMessage.Mutable newServerMessage();
    
    
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
    interface BayeuxServerListener extends Bayeux.BayeuxListener
    {}

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    public interface ChannelListener extends BayeuxServerListener
    {
        public void channelAdded(ServerChannel channel);
        public void channelRemoved(ServerChannel channel);
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
    public interface SubscriptionListener extends ServerChannelListener
    {
        public void subscribed(ServerSession session, ServerChannel channel);
        public void unsubscribed(ServerSession session, ServerChannel channel);
    }
}
