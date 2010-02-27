package org.cometd.bayeux.client;


import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;



/* ------------------------------------------------------------ */
/**
 * A Channel scoped to a Session.
 * <p>
 * A channel scoped to a particular {@link ClientSession}, so that subscriptions
 * and publishes to a SessionChannel are done on behalf of the associated {@link ClientSession}.
 * A SessionChannel may be for either an absolute channel (eg /foo/bar) or a
 * wild channel (eg /meta/* or /foo/**).
 * 
 */
public interface SessionChannel extends Channel
{
    void addListener(SessionChannelListener listener);
    void removeListener(SessionChannelListener listener);
    
    ClientSession getSession();

    void publish(Object data);
    void publish(Object data,Object id);
    
    void subscribe(SubscriberListener listener);
    void unsubscribe(SubscriberListener listener);
    void unsubscribe();

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    interface SessionChannelListener extends BayeuxListener
    {}

    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    public interface SubscriberListener extends BayeuxListener
    {
        void onMessage(SessionChannel channel, Message message);
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    public interface MessageListener extends SessionChannelListener
    {
        void onMessage(ClientSession session, Message message);
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    interface MetaChannelListener extends SessionChannelListener
    {
        void onMetaMessage(SessionChannel channel, Message message, boolean successful, String error);
    }
}
