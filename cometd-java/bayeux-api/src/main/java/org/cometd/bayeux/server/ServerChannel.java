package org.cometd.bayeux.server;

import java.util.Set;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;



/* ------------------------------------------------------------ */
/** Server side representation of a Bayeux Channel/
 *
 */
public interface ServerChannel extends Channel
{
    Set<ServerSession> getSubscribers();
    
    boolean isBroadcast();  // !meta and !service;
    boolean isLazy();
    boolean isPersistent();
    
    /* ------------------------------------------------------------ */
    /**
     * The ServerChannel is wild if it was obtained via an ID ending
     * with "/*".  Wild channels apply to all direct children of the
     * channel before the "/*".
     * @return true if the channel is wild.
     */
    boolean isWild();
    
    /* ------------------------------------------------------------ */
    /**
     * The ServerChannel is deeply wild if it was obtained via an ID ending
     * with "/**".  Deeply Wild channels apply to all descendants of the
     * channel before the "/**".
     * @return true if the channel is deeply wild.
     */
    boolean isDeepWild();
    
    /* ------------------------------------------------------------ */
    /** Set lazy channel
     * @param lazy If true, all messages published to this channel will
     * be marked as lazy.
     */
    void setLazy(boolean lazy);
    
    /* ------------------------------------------------------------ */
    /** Set persistent channel
     * @param persistent If true, the channel will not be removed when 
     * the last subscription is removed.
     */
    void setPersistent(boolean persistent);

    interface ServerChannelListener extends Channel.ChannelListener
    {}

    /* ------------------------------------------------------------ */
    public interface PublishListener extends ServerChannelListener
    {
        void onMessage(ServerMessage.Mutable message);
    }

    /* ------------------------------------------------------------ */
    public interface SubscriptionListener extends ServerChannelListener
    {
        /* ------------------------------------------------------------ */
        public void subscribed(ServerSession client, Channel channel);

        /* ------------------------------------------------------------ */
        public void unsubscribed(ServerSession client, Channel channel);
    }
}
