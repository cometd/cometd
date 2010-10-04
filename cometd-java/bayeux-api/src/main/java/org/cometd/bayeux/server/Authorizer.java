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

import java.util.EnumSet;

import org.cometd.bayeux.ChannelId;

/**
 * Cometd Authorizer.
 * <p>
 * A cometd {@link BayeuxServer} may have zero or more Authorizers that work 
 * together with the {@link SecurityPolicy} to determine if a handshake, channel create,
 * channel subscribe or publish operation may succeed.  
 * <p>
 * Each registered Authorizer may either permit, deny or ignore an operation.
 * An operation will only be permitted if all of the following are true:<ul>
 * <li>There is no SecurityPolicy or the corresponding method returned true</li>
 * <li>There are no Authorizers registered, or at least one registered Authorizer 
 * calls {@link Authorizer.Permission#granted()}.
 * <li>There are no Authorizers registered, or none of the registered Authorizers calls
 * {@link Authorizer.Permission#denied()}.
 * </ul>
 * <p>
 * Typically an Authorizer will be implemented using the information 
 * from {@link BayeuxServer#getContext()} to determine the users authentication.  
 *  
 */
public interface Authorizer
{
    /**
     * Operation enumeration.
     * <p>This enumeration is not used by this interface, but is provided
     * as a convenience for implementations.
     */
    enum Operation {Handshake, Create, Subscribe, Publish };
    
    public final static EnumSet<Operation> CreatePublishSubscribe=EnumSet.of(Operation.Create,Operation.Subscribe,Operation.Publish);
    public final static EnumSet<Operation> PublishSubscribe=EnumSet.of(Operation.Subscribe,Operation.Publish);
    
    /** Can a channel be created.
     * @param permission The permission to grant, deny or ignore.
     * @param server The Bayeux Server
     * @param session The session
     * @param channelId The channel to create
     * @param message The handshake message (immutable)
     */
    void canCreate(Permission permission, BayeuxServer server, ServerSession session, ChannelId channelId, ServerMessage message);

    /**
     * @param permission The permission to grant, deny or ignore.
     * @param server The Bayeux Server
     * @param session The session that will be created.
     * @param message The handshake message (immutable)
     */
    void canHandshake(Permission permission, BayeuxServer server, ServerSession session, ServerMessage message);

    /**
     * @param permission The permission to grant, deny or ignore.
     * @param server The Bayeux Server
     * @param session The session
     * @param channel The channel to publish to
     * @param message The publish message (immutable)
     */
    void canPublish(Permission permission, BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message);

    /**
     * @param permission The permission to grant, deny or ignore.
     * @param server The Bayeux Server
     * @param session The session
     * @param channel The channel to subscribe to
     * @param message The subscribe message (immutable)
     */
    void canSubscribe(Permission permission, BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message);

    /**
     * Permission interface
     *
     */
    interface Permission
    {
        /**
         * Grant permission. 
         */
        void granted();
        
        /**
         * Deny permission.
         */
        void denied();
        
        /**
         * Deny permission for a given reason.
         * @param reason The reason for denial.
         */
        void denied(String reason);
    }
}
