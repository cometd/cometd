package org.cometd.bayeux;




/* ------------------------------------------------------------ */
/** A Bayeux Channel.
 * <p>
 * A channel is the primary message routing mechanism within Bayeux:<ul>
 * <li> a bayeux client session uses the channel to route
 * a message to a particular listener.
 * <li> a bayeux Server uses the the channel of a message to select
 * a set of subscribed ServerSessions to which to deliver the message
 * </ul>
 * </p>
 * <p>
 * This interface is the common root for both the client side representation
 * of a Channel ({@link org.cometd.bayeux.client.ClientChannel}) and the 
 * server side representation of a Channel (@link {@link org.cometd.bayeux.server.ServerChannel}).
 * </p> 
 * <p>
 * Channels are identified with URI like paths (eg "/foo/bar").  Meta channels
 * have channel IDs starting with "/meta/" and are reserved for the
 * operation of they Bayeux protocol.    Service channels have
 * channel IDs starting with "/service/" and are channels for which
 * publish is disabled, so that only server side listeners will receive
 * the messages.
 * </p>
 * <p>
 * A Channel name may also be specified with wildcards. For 
 * example "/meta/*" refers to all top level meta channels 
 * like "/meta/subscribe". The channel "/foo/**" is deeply
 * wild and  refers to all channels like "/foo/bar",
 * "/foo/bar/bob" and "/foo/bar/wibble/bip".
 * </p>
 * 
 */
public interface Channel
{
    public final static String META_HANDSHAKE="/meta/handshake";
    public final static String META_CONNECT="/meta/connect";
    public final static String META_SUBSCRIBE="/meta/subscribe";
    public final static String META_UNSUBSCRIBE="/meta/unsubscribe";
    public final static String META_DISCONNECT="/meta/disconnect";

    /* ------------------------------------------------------------ */
    /**
     * @return The channel ID
     */
    String getId();

    /* ------------------------------------------------------------ */
    /**
     * @return true if the channel is a meta channel
     */
    boolean isMeta();

    /* ------------------------------------------------------------ */
    /**
     * @return true if the channel is a service channel
     */
    boolean isService();
    
    /* ------------------------------------------------------------ */
    /**
     * The ServerChannel is wild if it was obtained via an ID ending
     * with "/*".  Wild channels apply to all direct children of the
     * channel before the "/*".
     * @return true if the channel is wild.
     */
    boolean isWild();
    
    /* ------------------------------------------------------------ */
    /**
     * The ServerChannel is deeply wild if it was obtained via an ID ending
     * with "/**".  Deeply Wild channels apply to all descendants of the
     * channel before the "/**".
     * @return true if the channel is deeply wild.
     */
    boolean isDeepWild();
}
