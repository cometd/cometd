package org.cometd.bayeux;

import java.util.Set;

/**
 * <p>A Bayeux session represents a connection between a bayeux client and a bayeux server.</p>
 * <p>This interface is the common base interface for both the server side and the client side
 * representations of a session:</p>
 * <ul>
 * <li>if the remote client is not a Java client, then only a {@link org.cometd.bayeux.server.ServerSession}
 * instance will exist on the server and represents the remote client.</li>
 * <li>if the remote client is a Java client, then a {@link org.cometd.bayeux.client.ClientSession}
 * instance will exist on the client and a {@link org.cometd.bayeux.server.ServerSession}
 * instance will exist on the server, linked by the same clientId.</li>
 * <li>if the client is a Java client, but it is located in the server, then the
 * {@link org.cometd.bayeux.client.ClientSession} instance will be an instance
 * of {@link org.cometd.bayeux.server.LocalSession} and will be associated
 * with a {@link org.cometd.bayeux.server.ServerSession} instance.</li>
 * </ul>
 */
public interface Session
{
    /**
     * <p>The clientId of the session.</p>
     * <p>This would more correctly be called a "sessionId", but for
     * backwards compatibility with the Bayeux protocol, it is a field called "clientId"
     * that identifies a session.
     * @return the id of this session
     */
    String getId();

    /**
     * <p>A connected session is a session where the link between the client and the server
     * has been established.</p>
     * @return whether the session is connected
     * @see #disconnect()
     */
    boolean isConnected();

    /**
     * Disconnects this session, ending the link between the client and the server peers.
     * @see #isConnected()
     */
    void disconnect();

    /**
     * <p>Sets a named session attribute value.</p>
     * <p>Session attributes are convenience data that allows arbitrary
     * application data to be associated with a session.</p>
     * @param name the attribute name
     * @param value the attribute value
     */
    void setAttribute(String name, Object value);

    /**
     * <p>Retrieves the value of named session attribute.</p>
     * @param name the name of the attribute
     * @return the attribute value or null if the attribute is not present
     */
    Object getAttribute(String name);

    /**
     * @return the list of session attribute names.
     */
    Set<String> getAttributeNames();

    /**
     * <p>Removes a named session attribute.</p>
     * @param name the name of the attribute
     * @return the value of the attribute
     */
    Object removeAttribute(String name);

    /**
     * <p>Executes the given command in a batch so that any Bayeux message sent
     * by the command (via the Bayeux API) is queued up until the end of the
     * command and then all messages are sent at once.</p>
     * @param batch the Runnable to run as a batch
     */
    void batch(Runnable batch);

    /**
     * @deprecated use {@link #batch(Runnable)}
     */
    void endBatch();

    /**
     * @deprecated use {@link #batch(Runnable)}
     */
    void startBatch();
}
