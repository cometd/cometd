package org.cometd.bayeux;

import java.util.Set;

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
 *
 * @version $Revision: 1483 $ $Date: 2009-03-04 14:56:47 +0100 (Wed, 04 Mar 2009) $
 */
public interface Channel
{
    /** Constant representing the meta prefix */
    public static final String META = "/meta";
    /** Constant representing the handshake meta channel. */
    public final static String META_HANDSHAKE = META + "/handshake";
    /** Constant representing the connect meta channel */
    public final static String META_CONNECT = META + "/connect";
    /** Constant representing the subscribe meta channel */
    public final static String META_SUBSCRIBE = META + "/subscribe";
    /** Constant representing the unsubscribe meta channel */
    public final static String META_UNSUBSCRIBE = META + "/unsubscribe";
    /** Constant representing the disconnect meta channel */
    public final static String META_DISCONNECT = META + "/disconnect";

    /**
     * @return The channel id as a String
     */
    String getId();

    /**
     * @return The channel ID as a {@link ChannelId}
     */
    public ChannelId getChannelId();

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

    /**
     * <p>Sets a named channel attribute value.</p>
     * <p>Channel attributes are convenience data that allows arbitrary
     * application data to be associated with a channel.</p>
     * @param name the attribute name
     * @param value the attribute value
     */
    void setAttribute(String name, Object value);

    /**
     * <p>Retrieves the value of named channel attribute.</p>
     * @param name the name of the attribute
     * @return the attribute value or null if the attribute is not present
     */
    Object getAttribute(String name);

    /**
     * @return the list of channel attribute names.
     */
    Set<String> getAttributeNames();

    /**
     * <p>Removes a named channel attribute.</p>
     * @param name the name of the attribute
     * @return the value of the attribute
     */
    Object removeAttribute(String name);
}
