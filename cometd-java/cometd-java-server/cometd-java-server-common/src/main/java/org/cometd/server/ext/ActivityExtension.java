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

import java.util.concurrent.atomic.AtomicLong;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.NanoTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Monitors the activity of {@link ServerSession}s and disconnects them after a period of inactivity.</p>
 * <p>The inactivity of a particular {@code ServerSession} is determined in two ways:</p>
 * <ul>
 * <li>Only the client is inactive, that is the client only sends periodic {@code /meta/connect}
 * messages but no other messages, while the server may send messages to the client; this is
 * configured via {@link Activity#CLIENT}</li>
 * <li>Both the client and the server are inactive, that is neither the client nor the server
 * send messages apart the periodic {@code /meta/connect} messages; this is configured via
 * {@link Activity#CLIENT_SERVER}</li>
 * </ul>
 * <p>When the inactivity exceeds a configurable {@link #getMaxInactivityPeriod() inactive period},
 * the {@code ServerSession} is {@link ServerSession#disconnect() disconnected}.</p>
 */
public class ActivityExtension implements BayeuxServer.Extension {
    private final Activity activity;
    private final long maxInactivityPeriod;

    /**
     * Creates an ActivityExtension to be installed in the {@link BayeuxServer}
     *
     * @param activity            the activity to monitor
     * @param maxInactivityPeriod the max inactivity period, in milliseconds
     */
    public ActivityExtension(Activity activity, long maxInactivityPeriod) {
        this.activity = activity;
        this.maxInactivityPeriod = maxInactivityPeriod;
    }

    /**
     * @return the activity that is being monitored by this extension
     */
    public Activity getActivity() {
        return activity;
    }

    /**
     * @return the max inactivity period, in milliseconds
     */
    public long getMaxInactivityPeriod() {
        return maxInactivityPeriod;
    }

    @Override
    public boolean rcvMeta(ServerSession session, ServerMessage.Mutable message) {
        if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
            session.addExtension(newSessionExtension(session, message));
        }
        return true;
    }

    /**
     * Creates a new {@link ServerSession.Extension} that monitors the activity of the given {@link ServerSession}
     *
     * @param session   the {@code ServerSession} to monitor
     * @param handshake the handshake message
     * @return a new {@code ServerSession.Extension} that monitors the {@code ServerSession} activity
     */
    protected ServerSession.Extension newSessionExtension(ServerSession session, ServerMessage handshake) {
        return new SessionExtension(getActivity(), getMaxInactivityPeriod());
    }

    /**
     * The possible activity to monitor
     */
    public enum Activity {
        /**
         * Constant that indicates to monitor only client activity for a session
         */
        CLIENT,
        /**
         * Constant that indicates to monitor both client and server activity for a session
         */
        CLIENT_SERVER
    }

    /**
     * Monitors the activity of a single {@link ServerSession}, disconnecting it
     * when the max inactivity period is exceeded.
     */
    public static class SessionExtension implements ServerSession.Extension {
        private static final Logger logger = LoggerFactory.getLogger(ActivityExtension.class);
        private final AtomicLong lastActivity = new AtomicLong(NanoTime.now());
        private final Activity activity;
        private final long maxInactivityPeriod;

        public SessionExtension(Activity activity, long maxInactivityPeriod) {
            this.activity = activity;
            this.maxInactivityPeriod = maxInactivityPeriod;
        }

        /**
         * @return the max inactivity period, in milliseconds
         */
        public long getMaxInactivityPeriod() {
            return maxInactivityPeriod;
        }

        /**
         * @return the last activity timestamp, in nanoseconds
         */
        protected long getLastActivity() {
            return lastActivity.get();
        }

        @Override
        public boolean rcv(ServerSession session, ServerMessage.Mutable message) {
            if (logger.isDebugEnabled()) {
                logger.debug("Marking active {}, received message {}", session, message);
            }
            markActive();
            return true;
        }

        @Override
        public boolean rcvMeta(ServerSession session, ServerMessage.Mutable message) {
            if (Channel.META_CONNECT.equals(message.getChannel())) {
                if (isInactive()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Inactive {}, disconnecting", session);
                    }
                    disconnect(session);
                }
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("Marking active {}, received meta message {}", session, message);
                }
                markActive();
            }
            return true;
        }

        @Override
        public ServerMessage send(ServerSession sender, ServerSession session, ServerMessage message) {
            if (activity == Activity.CLIENT_SERVER) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Marking active {}, sending message {}", session, message);
                }
                markActive();
            }
            return message;
        }

        @Override
        public boolean sendMeta(ServerSession sender, ServerSession session, ServerMessage.Mutable message) {
            if (!Channel.META_CONNECT.equals(message.getChannel()) && activity == Activity.CLIENT_SERVER) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Marking active {}, sending meta message {}", session, message);
                }
                markActive();
            }
            return true;
        }

        protected void markActive() {
            lastActivity.set(NanoTime.now());
        }

        public boolean isInactive() {
            return NanoTime.millisSince(getLastActivity()) > getMaxInactivityPeriod();
        }

        protected void disconnect(ServerSession session) {
            if (session != null) {
                session.disconnect();
            }
        }
    }
}
