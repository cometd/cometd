// ========================================================================
// Copyright 2007 Dojo Foundation
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.cometd.bayeux.server;

/**
 * <p>A Bayeux {@link SecurityPolicy} defines the broad authorization constraints that must be enforced by
 * a {@link BayeuxServer}.
 * </p>
 * The usage of SecurityPolicy has been mostly replaced by the more flexible {@link Authorizer}.
 * ,p>
 * <p>A {@link BayeuxServer} may deny the handshake from clients that do not have proper
 * authentication credentials, or may deny clients to publish on reserved channels and so on;
 * all these activities are controlled by the {@link SecurityPolicy} implementation installed
 * on the {@link BayeuxServer}.
 * @see BayeuxServer#addAuthorizer(Authorizer)
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public interface SecurityPolicy
{
    /**
     * Checks if a message should be allowed to create a new channel.
     *
     * @param server the {@link BayeuxServer} object
     * @param session the client sending the message (may be null if an anonymous publish is attempted)
     * @param channelId the channel to be created
     * @param message the message trying to create the channel
     * @return true if the channel should be created
     */
    boolean canCreate(BayeuxServer server, ServerSession session, String channelId, ServerMessage message);

    /**
     * Checks if a handshake message should be accepted.
     *
     * @param server the {@link BayeuxServer} object
     * @param session the session (not yet added to the BayeuxServer)
     * @param message the handshake message
     * @return true if the handshake message should be accepted and the {@link ServerSession} instance
     * associated to the {@link BayeuxServer} object
     */
    boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message);

    /**
     * Checks if a client can publish a message to a channel.
     *
     * @param server the {@link BayeuxServer} object
     * @param session the client sending the message (may be null if an anonymous publish is attempted).
     * @param channel the channel to publish to
     * @param message the message to being published
     * @return true if the client can publish to the channel
     */
    boolean canPublish(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message);

    /**
     * Checks if a client is allowed to subscribe to a channel.
     *
     * @param server the {@link BayeuxServer} object
     * @param session the client sending the message (may be null if an anonymous subscribe is attempted).
     * @param channel the channel to subscribe to
     * @param message the subscribe message
     * @return true if the client can subscribe to the channel
     */
    boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message);
}
