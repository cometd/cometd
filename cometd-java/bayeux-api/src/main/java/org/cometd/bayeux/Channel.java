package org.cometd.bayeux;



/** A Bayeux Channel.
 * <p>
 * A channel is the primary message routing mechanism within Bayeux:<ul>
 * <li> a bayeux client session uses the channel to route
 * a message to a particular listener.
 * <li> a bayeux Server uses the the channel of a message to select
 * a set of subscribed ServerSessions to which to deliver the message
 * </ul>
 * <p>
 * Channels are identified with URI like paths (eg /foo/bar).  Meta channels
 * have channel IDs starting with "/meta/" and are reserved for the
 * operation of they Bayeux protocol.    Service channels have
 * channel IDs starting with "/service/" and are channels for which
 * publish is disabled, so that only server side listeners will receive
 * the messages.
 */
public interface Channel
{
    public final static String META_HANDSHAKE="/meta/handshake";
    public final static String META_CONNECT="/meta/connect";
    public final static String META_SUBSCRIBE="/meta/subscribe";
    public final static String META_UNSUBSCRIBE="/meta/unsubscribe";
    public final static String META_DISCONNECT="/meta/disconnect";
    
    /**
     * @return The channel ID
     */
    String getId();

    /**
     * @return true if the channel is a meta channel
     */
    boolean isMeta();

    /**
     * @return true if the channel is a service channel
     */
    boolean isService();

    /** Add a channel listener
     * @param listener A Listener for events on this channel
     */
    void addListener(ChannelListener listener);

    /** Remove a channel listener
     * @param listener A Listener for events on this channel
     */
    void removeListener(ChannelListener listener);

    interface ChannelListener extends  Bayeux.BayeuxListener
    {
    }

    /**
     * Listener for all messages on a channel
     */
    public interface MessageListener extends ChannelListener
    {
        void onMessage(Bayeux bayeux, Channel channel, Message message);
    }

    /**
     * Listener for all meta messages on a channel
     */
    public interface MetaListener extends ChannelListener
    {
        void onMetaMessage(Bayeux bayeux, Channel channel, Message message,boolean successful,String error);
    }
}
