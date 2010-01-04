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
    
    void setLazy(boolean lazy);
    void setPersistent(boolean persistent);

    interface ServerChannelListener extends Channel.ChannelListener
    {}

    /* ------------------------------------------------------------ */
    public interface PublishListener extends ServerChannelListener
    {
        void onMessage(ServerMessage.Mutable message);
    }

    public interface SubscriptionListener extends ServerChannelListener
    {
        /* ------------------------------------------------------------ */
        public void subscribed(ServerSession client, Channel channel);

        /* ------------------------------------------------------------ */
        public void unsubscribed(ServerSession client, Channel channel);
    }
}
