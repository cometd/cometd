package org.cometd.bayeux;

/**
 * <p>A Bayeux channel is the primary message routing mechanism within Bayeux:
 * both Bayeux clients and Bayeux server use channels to group listeners that
 * are interested in receiving messages with that channel.</p>
 *
 * <p>This interface is the common root for both the
 * {@link org.cometd.bayeux.client.ClientSessionChannel client side} representation
 * of a channel and the {@link org.cometd.bayeux.server.ServerChannel server side}
 * representation of a channel.</p>
 *
 * <p>Channels are identified with strings that look like paths (e.g. "/foo/bar")
 * called "channel id".<br/>
 * Meta channels have channel ids starting with "/meta/" and are reserved for the
 * operation of they Bayeux protocol.<br/>
 * Service channels have channel ids starting with "/service/" and are channels
 * for which publish is disabled, so that only server side listeners will receive
 * the messages.</p>
 *
 * <p>A channel id may also be specified with wildcards.<br/>
 * For example "/meta/*" refers to all top level meta channels
 * like "/meta/subscribe" or "/meta/handshake".<br/>
 * The channel "/foo/**" is deeply wild and refers to all channels like "/foo/bar",
 * "/foo/bar/bob" and "/foo/bar/wibble/bip".<br/>
 * Wildcards can only be specified as last segment of a channel; therefore channel
 * "/foo/&#42;/bar/** is an invalid channel.</p>
 */
public interface Channel
{
    /** Constant representing the handshake meta channel. */
    public final static String META_HANDSHAKE="/meta/handshake";
    /** Constant representing the connect meta channel */
    public final static String META_CONNECT="/meta/connect";
    /** Constant representing the subscribe meta channel */
    public final static String META_SUBSCRIBE="/meta/subscribe";
    /** Constant representing the unsubscribe meta channel */
    public final static String META_UNSUBSCRIBE="/meta/unsubscribe";
    /** Constant representing the disconnect meta channel */
    public final static String META_DISCONNECT="/meta/disconnect";

    /**
     * @return The channel id
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

    /**
     * @return true if the channel is wild.
     */
    boolean isWild();

    /**
     * @return true if the channel is deeply wild.
     */
    boolean isDeepWild();
}
