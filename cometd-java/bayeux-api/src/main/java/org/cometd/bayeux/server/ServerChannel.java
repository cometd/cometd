package org.cometd.bayeux.server;

import java.util.Set;

import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.client.SessionChannel;



/* ------------------------------------------------------------ */
/** Server side representation of a Bayeux Channel.
 * <p>
 * The ServerChannel is the entity that holds the set of 
 * {@link ServerSession}s that are subscribed to a channel.
 * A message published to a ServerChannel will be delivered to
 * all the {@link ServerSession}'s subscribed to the channel.
 * </p>
 * <p>A ServerChannel is distinct from a {@link SessionChannel},
 * which is the client side representation of a channel (note there
 * can be clients within the server JVM).
 * </p>
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
     * Publish a message to the channel.
     * <p>
     * Delivered a message to all the {@link ServerSession}'s 
     * subscribed to the channel.
     * 
     * @param message
     */
    void publish(Session from, ServerMessage message);

    /* ------------------------------------------------------------ */
    /**
     * Publish a message to the channel.
     * <p>
     * Delivered a message to all the {@link ServerSession}'s 
     * subscribed to the channel.
     * @param data 
     */
    void publish(Session from, Object data, Object id);


    /* ------------------------------------------------------------ */
    /** Remove a channel
     */
    void remove();
    
    
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
