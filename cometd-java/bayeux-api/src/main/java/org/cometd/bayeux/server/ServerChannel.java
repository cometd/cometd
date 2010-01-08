package org.cometd.bayeux.server;

import java.util.Set;

import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.client.ClientChannel.ClientChannelListener;



/* ------------------------------------------------------------ */
/** Server side representation of a Bayeux Channel/
 *
 */
public interface ServerChannel extends Channel
{
    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    void addListener(ServerChannelListener listener);
    
    /* ------------------------------------------------------------ */
    /**
     * @param listener
     */
    void removeListener(ServerChannelListener listener);
    
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    Set<? extends ServerSession> getSubscribers();
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    boolean isBroadcast();  // !meta and !service;
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    boolean isLazy();
    
    /* ------------------------------------------------------------ */
    /**
     * @return
     */
    boolean isPersistent();
    
    
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


    /* ------------------------------------------------------------ */
    /**
     * @param message
     */
    void publish(ServerSession from, ServerMessage message);
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    interface ServerChannelListener extends BayeuxListener
    {}

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    public interface MessageListener extends ServerChannelListener
    {
        boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message);
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    public interface SubscriptionListener extends ServerChannelListener
    {
        public void subscribed(ServerSession session, ServerChannel channel);
        public void unsubscribed(ServerSession session, ServerChannel channel);
    }
}
