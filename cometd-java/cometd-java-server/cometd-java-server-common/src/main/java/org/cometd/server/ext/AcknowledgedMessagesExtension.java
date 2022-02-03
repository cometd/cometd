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
package org.cometd.server.ext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.ServerSessionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Installing this extension in a {@link BayeuxServer} provides support for
 * server-to-client message acknowledgement if a client also supports it.</p>
 * <p>The main role of this extension is to install the
 * {@link AcknowledgedMessagesSessionExtension} on the {@link ServerSession}
 * instances created during successful handshakes.</p>
 */
public class AcknowledgedMessagesExtension implements Extension {
    private final Logger _logger = LoggerFactory.getLogger(getClass().getName());
    private final List<Listener> _listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener listener) {
        _listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        _listeners.remove(listener);
    }

    @Override
    public boolean rcvMeta(ServerSession remote, Mutable message) {
        if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
            Map<String, Object> rcvExt = message.getExt();
            boolean clientRequestedAcks = rcvExt != null && rcvExt.get("ack") == Boolean.TRUE;

            if (clientRequestedAcks && remote != null) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Enabled message acknowledgement for {}", remote);
                }

                AcknowledgedMessagesSessionExtension extension = newSessionExtension(remote);
                extension.addListeners(_listeners);

                // Make sure that adding the extension and importing the queue is atomic.
                ServerSessionImpl session = (ServerSessionImpl)remote;
                synchronized (session.getLock()) {
                    session.addExtension(extension);
                    extension.importMessages(session);
                }
            }
        }
        return true;
    }

    protected AcknowledgedMessagesSessionExtension newSessionExtension(ServerSession session) {
        return new AcknowledgedMessagesSessionExtension(session);
    }

    /**
     * <p>A listener for acknowledgement events.</p>
     * <p>Implementation will be notified of these events:</p>
     * <ul>
     *   <li>{@link #onBatchSend(ServerSession, List, long) batchSend}, when a batch of messages is sent to a client session</li>
     *   <li>{@link #onBatchReceive(ServerSession, long)}, when the client session confirms it has received a batch of messages</li>
     * </ul>
     * <p>Stateful implementations may use {@link ServerSession#setAttribute(String, Object)}
     * to store per-session data, or a {@code Map&lt;ServerSession, ?&gt;}</p>
     */
    public interface Listener {
        /**
         * <p>Callback method invoked when a batch of message is about to be sent to a client session.</p>
         *
         * @param session the session
         * @param messages the messages to send, as an immutable list
         * @param batch the batch number
         */
        default void onBatchSend(ServerSession session, List<ServerMessage> messages, long batch) {
        }

        /**
         * <p>Callback method invoked when a client session confirms it has received the given batch of messages.</p>
         *
         * @param session the session
         * @param batch the batch number
         */
        default void onBatchReceive(ServerSession session, long batch) {
        }
    }
}
