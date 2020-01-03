/*
 * Copyright (c) 2008-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.bayeux.client;

import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;

/**
 * <p>This interface represents the client side Bayeux session.</p>
 * <p>In addition to the {@link Session common Bayeux session}, this
 * interface provides method to configure extension, access channels
 * and to initiate the communication with a Bayeux server(s).</p>
 */
public interface ClientSession extends Session {
    /**
     * Adds an extension to this session.
     *
     * @param extension the extension to add
     * @see #removeExtension(Extension)
     */
    void addExtension(Extension extension);

    /**
     * Removes an extension from this session.
     *
     * @param extension the extension to remove
     * @see #addExtension(Extension)
     */
    void removeExtension(Extension extension);

    /**
     * @return an immutable list of extensions present in this ClientSession instance
     * @see #addExtension(Extension)
     */
    List<Extension> getExtensions();

    /**
     * <p>Equivalent to {@link #handshake(Map) handshake(null)}.</p>
     */
    void handshake();

    /**
     * <p>Equivalent to {@link #handshake(Map, ClientSessionChannel.MessageListener) handshake(template, null)}.</p>
     *
     * @param template additional fields to add to the handshake message.
     */
    void handshake(Map<String, Object> template);

    /**
     * <p>Initiates the bayeux protocol handshake with the server(s).</p>
     * <p>The handshake initiated by this method is asynchronous and
     * does not wait for the handshake response.</p>
     *
     * @param template additional fields to add to the handshake message.
     * @param callback the message listener to notify of the handshake result
     */
    void handshake(Map<String, Object> template, ClientSessionChannel.MessageListener callback);

    /**
     * <p>Disconnects this session, ending the link between the client and the server peers.</p>
     *
     * @param callback the message listener to notify of the disconnect result
     */
    void disconnect(ClientSessionChannel.MessageListener callback);

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
     *
     * @param channelName specific or wild channel name.
     * @return a channel scoped by this session.
     */
    ClientSessionChannel getChannel(String channelName);

    /**
     * <p>Performs a remote call to the server, to the specified {@code target},
     * and with the given {@code data} as payload.</p>
     * <p>The remote call response will be delivered via the {@code callback}
     * parameter.</p>
     * <p>Typical usage:</p>
     * <pre>
     * clientSession.remoteCall("getOnlineStatus", userId, new MessageListener()
     * {
     *     &#64;Override
     *     public void onMessage(Message message)
     *     {
     *         if (message.isSuccessful())
     *         {
     *             String status = (String)message.getData();
     *             // Update UI with online status.
     *         }
     *         else
     *         {
     *             // Remote call failed.
     *         }
     *     }
     * });
     * </pre>
     *
     * @param target   the remote call target
     * @param data     the remote call parameters
     * @param callback the listener that receives the remote call response
     */
    void remoteCall(String target, Object data, MessageListener callback);

    /**
     * <p>A listener for remote call messages.</p>
     *
     * @see #remoteCall(String, Object, MessageListener)
     */
    public interface MessageListener extends Bayeux.BayeuxListener {
        /**
         * Callback invoked when a remote call response is received.
         *
         * @param message the remote call response
         */
        void onMessage(Message message);
    }

    /**
     * <p>Extension API for client session.</p>
     * <p>An extension allows user code to interact with the Bayeux protocol as late
     * as messages are sent or as soon as messages are received.</p>
     * <p>Messages may be modified, or state held, so that the extension adds a
     * specific behavior simply by observing the flow of Bayeux messages.</p>
     *
     * @see ClientSession#addExtension(Extension)
     */
    public interface Extension {
        /**
         * Callback method invoked every time a normal message is received.
         *
         * @param session the session object that is receiving the message
         * @param message the message received
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcv(ClientSession session, Message.Mutable message);

        /**
         * Callback method invoked every time a meta message is received.
         *
         * @param session the session object that is receiving the meta message
         * @param message the meta message received
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcvMeta(ClientSession session, Message.Mutable message);

        /**
         * Callback method invoked every time a normal message is being sent.
         *
         * @param session the session object that is sending the message
         * @param message the message being sent
         * @return true if message processing should continue, false if it should stop
         */
        boolean send(ClientSession session, Message.Mutable message);

        /**
         * Callback method invoked every time a meta message is being sent.
         *
         * @param session the session object that is sending the message
         * @param message the meta message being sent
         * @return true if message processing should continue, false if it should stop
         */
        boolean sendMeta(ClientSession session, Message.Mutable message);

        /**
         * Empty implementation of {@link Extension}.
         */
        public static class Adapter implements Extension {
            @Override
            public boolean rcv(ClientSession session, Message.Mutable message) {
                return true;
            }

            @Override
            public boolean rcvMeta(ClientSession session, Message.Mutable message) {
                return true;
            }

            @Override
            public boolean send(ClientSession session, Message.Mutable message) {
                return true;
            }

            @Override
            public boolean sendMeta(ClientSession session, Message.Mutable message) {
                return true;
            }
        }
    }
}
