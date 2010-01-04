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
    /**
     * @param clientId
     * @return
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
     * Return a message suitable for use with the {@link #publish(Message)} or
     * {@link ServerSession#deliver(ServerMessage)} methods.
     * @return A new or recycled message instance.
     */
    ServerMessage.Mutable newMessage();
    
    /* ------------------------------------------------------------ */
    /**
     * @param message
     */
    void publish(Message message);
    
    /* ------------------------------------------------------------ */
    /**
     * @param securityPolicy
     */
    public void setSecurityPolicy(SecurityPolicy securityPolicy);


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
        public void channelAdded(BayeuxServer server, Channel channel);
        public void channelRemoved(BayeuxServer server, Channel channel);
    };

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    public interface SessionListener extends BayeuxServerListener
    {
        public void sessionAdded(BayeuxServer server, ServerSession channel);
        public void sessionRemoved(BayeuxServer server, ServerSession channel,boolean timedout);
    }

    public interface SubscriptionListener extends ServerChannelListener
    {
        public void subscribed(ServerSession session, Channel channel);
        public void unsubscribed(ServerSession session, Channel channel);
    }
}
