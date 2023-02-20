/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.bayeux;

import java.util.Set;

/**
 * <p>A Bayeux session represents a connection between a bayeux client and a bayeux server.</p>
 * <p>This interface is the common base interface for both the server side and the client side
 * representations of a session:</p>
 * <ul>
 * <li>on server-side a session represents the remote client.</li>
 * <li>on a client-side Java client a client session exists, together with a server-side
 * session on the server, linked by the same session id.</li>
 * <li>on a server-side Java client the client session is a local session and it is
 * associated with a server-side session.</li>
 * </ul>
 */
public interface Session {
    /**
     * <p>The clientId of the session.</p>
     * <p>This would more correctly be called a "sessionId", but for
     * backwards compatibility with the Bayeux protocol, it is a field called "clientId"
     * that identifies a session.
     *
     * @return the id of this session
     */
    String getId();

    /**
     * <p>A connected session is a session where the link between the client and the server
     * has been established.</p>
     *
     * @return whether the session is connected
     * @see #disconnect()
     */
    boolean isConnected();

    /**
     * <p>A handshook session is a session where the handshake has successfully completed</p>
     *
     * @return whether the session is handshook
     */
    boolean isHandshook();

    /**
     * Disconnects this session, ending the link between the client and the server peers.
     *
     * @see #isConnected()
     */
    void disconnect();

    /**
     * <p>Sets a named session attribute value.</p>
     * <p>Session attributes are convenience data that allows arbitrary
     * application data to be associated with a session.</p>
     *
     * @param name  the attribute name
     * @param value the attribute value
     */
    void setAttribute(String name, Object value);

    /**
     * <p>Retrieves the value of named session attribute.</p>
     *
     * @param name the name of the attribute
     * @return the attribute value or null if the attribute is not present
     */
    Object getAttribute(String name);

    /**
     * @return the session attribute names.
     */
    Set<String> getAttributeNames();

    /**
     * <p>Removes a named session attribute.</p>
     *
     * @param name the name of the attribute
     * @return the value of the attribute
     */
    Object removeAttribute(String name);

    /**
     * <p>Executes the given command in a batch so that any Bayeux message sent
     * by the command (via the Bayeux API) is queued up until the end of the
     * command and then all messages are sent at once.</p>
     *
     * @param batch the Runnable to run as a batch
     */
    void batch(Runnable batch);

    /**
     * <p>Starts a batch, to be ended with {@link #endBatch()}.</p>
     * <p>The {@link #batch(Runnable)} method should be preferred since it automatically
     * starts and ends a batch without relying on a try/finally block.</p>
     * <p>This method is to be used in the cases where the use of {@link #batch(Runnable)}
     * is not possible or would make the code more complex.</p>
     *
     * @see #endBatch()
     * @see #batch(Runnable)
     */
    void startBatch();

    /**
     * <p>Ends a batch started with {@link #startBatch()}.</p>
     *
     * @return true if the batch ended and there were messages to send.
     * @see #startBatch()
     */
    boolean endBatch();
}
