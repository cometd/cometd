package org.cometd.bayeux;

import java.util.EventListener;

/**
 * <p>The Bayeux interface represents the API for non session related configuration, extensions and listeners.</p>
 * <p>Both client and server Bayeux interfaces are derived from this common interface.</p>
 */
public interface Bayeux
{
    /**
     * Adds the given extension to this bayeux object.
     * @param extension the extension to add
     * @see #removeExtension(Extension)
     */
    void addExtension(Extension extension);

    /**
     * Removes the given extension from this bayeux object.
     * @param extension the extension to remove
     * @see #addExtension(Extension)
     */
    void removeExtension(Extension extension);

    /**
     * <p>Returns a {@link Channel} with the given channelId. <br/>
     * The actual implementation class will depend on the (client or server) {@link Bayeux} implementation.</p>
     * @param channelId the channel identifier
     * @return a {@link Channel} instance with the given identifier
     */
    Channel getChannel(String channelId);

    /**
     * <p>Adds the given listener to this bayeux object.</p>
     * <p>The listener class must extend from {@link BayeuxListener}, and be
     * suitable for the (client or server) {@link Bayeux} implementation.</p>
     * @throws IllegalArgumentException if the type of the listener is not supported
     * @param listener the listener to be added
     * @see #removeListener(BayeuxListener)
     */
    void addListener(BayeuxListener listener) throws IllegalArgumentException;

    /**
     * Removes the given listener from this bayeux object.
     * @param listener the listener to be removed.
     * @see #addListener(BayeuxListener)
     */
    void removeListener(BayeuxListener listener);

    /**
     * <p>The base interface for listeners interested in Bayeux events. <br/>
     * All Bayeux listener interfaces are derived from this interface.</p>
     */
    interface BayeuxListener extends EventListener
    {
    }

    /**
     * <p>Extension API for bayeux messages.</p>
     * <p>Implementations of this interface allow to modify incoming and outgoing messages
     * respectively just before and just after they are handled by the implementation,
     * either on client side or server side.</p>
     * <p>Extensions are be registered in order and one extension may allow subsequent
     * extensions to process the message by returning true from the callback method, or
     * forbid further processing by returning false.</p>
     *
     * @see Bayeux#addExtension(Extension)
     */
    public interface Extension
    {
        /**
         * Callback method invoked every time a normal message is incoming.
         * @param session the session object
         * @param message the incoming message
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcv(Session session, Message.Mutable message);

        /**
         * Callback method invoked every time a meta message is incoming.
         * @param session the session object
         * @param message the incoming meta message
         * @return true if message processing should continue, false if it should stop
         */
        boolean rcvMeta(Session session, Message.Mutable message);

        /**
         * Callback method invoked every time a normal message is outgoing.
         * @param session the session object
         * @param message the outgoing message
         * @return true if message processing should continue, false if it should stop
         */
        boolean send(Session session, Message.Mutable message);

        /**
         * Callback method invoked every time a meta message is outgoing.
         * @param session the session object
         * @param message the outgoing meta message
         * @return true if message processing should continue, false if it should stop
         */
        boolean sendMeta(Session session, Message.Mutable message);

        /**
         * Adapter for the {@link Extension} interface that always returns true from callback methods.
         */
        public static class Adapter implements Extension
        {
            public boolean rcv(Session session, Message.Mutable message)
            {
                return true;
            }

            public boolean rcvMeta(Session session, Message.Mutable message)
            {
                return true;
            }

            public boolean send(Session session, Message.Mutable message)
            {
                return true;
            }

            public boolean sendMeta(Session session, Message.Mutable message)
            {
                return true;
            }
        }
    }
}
