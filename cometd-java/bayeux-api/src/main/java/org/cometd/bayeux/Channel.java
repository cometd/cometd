package org.cometd.bayeux;

import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.client.BayeuxClient.BayeuxClientListener;

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
    public interface MetaListener extends BayeuxClientListener
    {
        void onMetaMessage(Bayeux bayeux, Channel channel, Message message,boolean successful,String error);
    }

    // TODO: move this to a separate class ?
    public enum MetaChannelId
    {
        HANDSHAKE("/meta/handshake"),
        CONNECT("/meta/connect"),
        SUBSCRIBE("/meta/subscribe"),
        UNSUBSCRIBE("/meta/unsubscribe"),
        DISCONNECT("/meta/disconnect");

        private final String _channelId;

        private MetaChannelId(String channelId)
        {
            _channelId = channelId;
            MetaChannelIds.values.put(channelId, this);
        }

        public String getChannelId()
        {
            return _channelId;
        }

        public static MetaChannelId from(String channelId)
        {
            return MetaChannelIds.values.get(channelId);
        }

        private static class MetaChannelIds
        {
            private static Map<String, MetaChannelId> values = new HashMap<String, MetaChannelId>();
        }
    }
}
