package org.cometd.bayeux.client;

import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;

/**
 * <p>This interface represents the client side Bayeux session.</p>
 * <p>In addition to the {@link Session common Bayeux session}, this
 * interface provides method to configure extension, access channels
 * and to initiate the communication with a Bayeux server(s).</p>
 *
 * @version $Revision: 1483 $ $Date: 2009-03-04 14:56:47 +0100 (Wed, 04 Mar 2009) $
 */
public interface ClientSession extends Session
{
    /**
     * Adds an extension to this session.
     * @param extension the extension to add
     * @see #removeExtension(Extension)
     */
    void addExtension(Extension extension);

    /**
     * Removes an extension from this session.
     * @param extension the extension to remove
     * @see #addExtension(Extension)
     */
    void removeExtension(Extension extension);

    /**
     * <p>Equivalent to {@link #handshake(Map) handshake(null)}.</p>
     */
    void handshake();

    /**
     * <p>Initiates the bayeux protocol handshake with the server(s).</p>
     * <p>The handshake initiated by this method is asynchronous and
     * does not wait for the handshake response.</p>
     *
     * @param template additional fields to add to the handshake message.
     */
    void handshake(Map<String, Object> template);

    /**
     * <p>Returns a client side channel scoped by this session.</p>
     * <p>The channel name may be for a specific channel (e.g. "/foo/bar")
     * or for a wild channel (e.g. "/meta/**" or "/foo/*").</p>
     * <p>This method will always return a channel, even if the
     * the channel has not been created on the server side.  The server
     * side channel is only involved once a publish or subscribe method
     * is called on the channel returned by this method.</p>
     * <p>Typical usage examples are:</p>
     * <pre>
     *     clientSession.getChannel("/foo/bar").subscribe(mySubscriptionListener);
     *     clientSession.getChannel("/foo/bar").publish("Hello");
     *     clientSession.getChannel("/meta/*").addListener(myMetaChannelListener);
     * </pre>
     * @param channelName specific or wild channel name.
     * @return a channel scoped by this session.
     */
    ClientSessionChannel getChannel(String channelName);

    /**
     * <p>Extension API for client session.</p>
     * <p>An extension allows user code to interact with the Bayeux protocol as late
     * as messages are sent or as soon as messages are received.</p>
     * <p>Messages may be modified, or state held, so that the extension adds a
     * specific behavior simply by observing the flow of Bayeux messages.</p>
     *
     * @see ClientSession#addExtension(Extension)
     */
    public interface Extension
    {
        /**
         * Callback method invoked every time a normal message is received.
         * @param session the session object that is receiving the message
         * @param message the message received
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcv(ClientSession session, Message.Mutable message);

        /**
         * Callback method invoked every time a meta message is received.
         * @param session the session object that is receiving the meta message
         * @param message the meta message received
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcvMeta(ClientSession session, Message.Mutable message);

        /**
         * Callback method invoked every time a normal message is being sent.
         * @param session the session object that is sending the message
         * @param message the message being sent
         * @return true if message processing should continue, false if it should stop
         */
        boolean send(ClientSession session, Message.Mutable message);

        /**
         * Callback method invoked every time a meta message is being sent.
         * @param session the session object that is sending the message
         * @param message the meta message being sent
         * @return true if message processing should continue, false if it should stop
         */
        boolean sendMeta(ClientSession session, Message.Mutable message);
    }
}
