package org.cometd.bayeux.client;


import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;



/* ------------------------------------------------------------ */
/**
 * A client side Channel representation.
 * <p>
 * A SessionChannel is scoped to a particular {@link ClientSession} that is 
 * obtained by a call to {@link ClientSession#getChannel(String)}.
 * </p><p>
 * Typical usage examples are: <pre>
 *     clientSession.getChannel("/foo/bar").subscribe(mySubscriptionListener);
 *     clientSession.getChannel("/foo/bar").publish("Hello");
 *     clientSession.getChannel("/meta/*").addListener(myMetaChannelListener);
 * <pre>
 * 
 * <p>
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
