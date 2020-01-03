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
package org.cometd.bayeux.server;

import org.cometd.bayeux.Session;
import org.cometd.bayeux.client.ClientSessionChannel;

/**
 * <p>A {@link SecurityPolicy} defines the broad authorization constraints that must be
 * enforced by a {@link BayeuxServer}.</p>
 * <p>The usage of {@link SecurityPolicy} has been mostly replaced by the usage of the
 * more flexible {@link Authorizer} for creation of channels, subscription to channels
 * and publish to channels.
 * {@link SecurityPolicy} is still the central authorization component for handshakes.</p>
 * <p>A {@link BayeuxServer} may deny the handshake from clients that do not have
 * proper authentication credentials, or may deny clients to publish on reserved
 * channels and so on; all these activities are controlled by the {@link SecurityPolicy}
 * implementation installed on the {@link BayeuxServer} via
 * {@link BayeuxServer#setSecurityPolicy(SecurityPolicy)}.</p>
 *
 * @see ServerChannel#addAuthorizer(Authorizer)
 */
public interface SecurityPolicy {
    /**
     * <p>Checks if a handshake message should be accepted.</p>
     * <p>Both remote sessions and local sessions are subject to this check.
     * Applications usually want local sessions (that is, server-side only sessions related to services)
     * to always pass this check, so a typical implementation filters local session using
     * {@link ServerSession#isLocalSession()}.</p>
     *
     * @param server  the {@link BayeuxServer} object
     * @param session the session (not yet added to the BayeuxServer)
     * @param message the handshake message
     * @return true if the handshake message should be accepted and the {@link ServerSession} instance
     * associated to the {@link BayeuxServer} object
     */
    boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message);

    /**
     * <p>Checks if a message should be allowed to create a new channel.</p>
     * <p>A subscribe message or publish message to a channel not yet known to the server triggers this check.
     * Both remote sessions and local sessions, when performing subscribes or publishes via
     * {@link ClientSessionChannel#subscribe(ClientSessionChannel.MessageListener)} or
     * {@link ClientSessionChannel#publish(Object)} are therefore subject to this check.</p>
     * <p>Direct calls to {@link BayeuxServer#createChannelIfAbsent(String, ConfigurableServerChannel.Initializer...)}
     * are not subject to this check.</p>
     *
     * @param server    the {@link BayeuxServer} object
     * @param session   the client sending the message
     * @param channelId the channel to be created
     * @param message   the message trying to create the channel
     * @return true if the channel should be created
     */
    boolean canCreate(BayeuxServer server, ServerSession session, String channelId, ServerMessage message);

    /**
     * <p>Checks if a subscribe message from a client is allowed to subscribe to a channel.</p>
     * <p>Both remote and local sessions are subject to this check when performing subscribes via
     * {@link ClientSessionChannel#subscribe(ClientSessionChannel.MessageListener)}.</p>
     * <p>{@link ServerChannel#subscribe(ServerSession)} is not subject to this check.</p>
     *
     * @param server  the {@link BayeuxServer} object
     * @param session the client sending the message
     * @param channel the channel to subscribe to
     * @param message the subscribe message
     * @return true if the client can subscribe to the channel
     */
    boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message);

    /**
     * <p>Checks if a client can publish a message to a channel.</p>
     * <p>Both remote and local sessions are subject to this check when performing publishes via
     * {@link ClientSessionChannel#publish(Object)}.</p>
     * <p>{@link ServerChannel#publish(Session, Object)} is not subject to this check.</p>
     *
     * @param server  the {@link BayeuxServer} object
     * @param session the client sending the message
     * @param channel the channel to publish to
     * @param message the message to being published
     * @return true if the client can publish to the channel
     */
    boolean canPublish(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message);
}
