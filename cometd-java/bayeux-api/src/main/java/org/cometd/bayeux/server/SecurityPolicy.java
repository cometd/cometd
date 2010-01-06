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

import org.cometd.bayeux.Session;

/**
 * Pluggable security policy for Bayeux
 * 
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public interface SecurityPolicy
{
    /**
     * Test if a message should be allowed to create a new Channel
     *
     * @param client  The client sending the message. The client may be
     *                null if an anonymous publish is attempted. Server clients are
     *                indicated by {@link Session#isLocal()}
     * @param message The message
     * @return true if the channel should be created
     */
    boolean canCreate(BayeuxServer server,ServerSession session, ServerMessage message);

    /**
     * Test if a handshake message should be accepted.
     *
     * @param message A handshake message.
     * @param client  The session (not yet added to the BayeuxServer) 
     * @return True if the handshake message should be accepted and a {@link Session} instance created
     */
    boolean canHandshake(BayeuxServer server,ServerSession session,ServerMessage message);

    /**
     * Test if a client can publish a message to a channel
     *
     * @param client   The client sending the message. The client may be
     *                 null if an anonymous publish is attempted. Server clients are
     *                 indicated by {@link Session#isLocal()}
     * @param messsage The message to publish
     * @return true if the client can publish to the channel.
     */
    boolean canPublish(BayeuxServer server,ServerSession client, ServerMessage.Mutable messsage);

    /**
     * Test if a client is allowed to subscribe to a channel
     *
     * @param client   The client sending the message. The client may be
     *                 null if an anonymous publish is attempted. Server clients are
     *                 indicated by {@link Session#isLocal()}
     * @param messsage The message to /meta/subscribe
     * @return true if the client can subscribe to the channel
     */
    boolean canSubscribe(BayeuxServer server,ServerSession client, ServerMessage messsage);
}
