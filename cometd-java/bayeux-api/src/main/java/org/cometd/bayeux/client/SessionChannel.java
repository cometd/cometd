package org.cometd.bayeux.client;


import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;


public interface SessionChannel extends Channel
{
    void addListener(SessionChannelListener listener);
    void removeListener(SessionChannelListener listener);
    
    ClientSession getSession();

    void publish(Object data);
    void publish(Object data,Object id);
    
    void subscribe(SubscriptionListener listener);
    void unsubscribe(SubscriptionListener listener);
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
    public interface SubscriptionListener extends BayeuxListener
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
