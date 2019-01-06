/*
 * Copyright (c) 2008-2019 the original author or authors.
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

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;

/**
 * <p>{@link Authorizer}s authorize {@link Operation operations} on {@link ServerChannel channels}.</p>
 * <p>Authorizers can be {@link ConfigurableServerChannel#addAuthorizer(Authorizer) added to} and
 * {@link ConfigurableServerChannel#removeAuthorizer(Authorizer)}  removed from} channels, even wildcard
 * channels.</p>
 * <p>{@link Authorizer}s work together with the {@link SecurityPolicy} to determine if a
 * {@link Operation#CREATE channel creation}, a {@link Operation#SUBSCRIBE channel subscribe} or a
 * {@link Operation#PUBLISH publish operation} may succeed.
 * </p>
 * <p>For an operation on a channel, the authorizers on the wildcard channels that match the channel and the
 * authorizers on the channel itself (together known at the <em>authorizers set</em> for that channel) will be
 * consulted to check if the the operation is granted, denied or ignored.</p>
 * <p>The list of wildcard channels that match the channel is obtained from {@link ChannelId#getWilds()}.</p>
 * <p>The following is the authorization algorithm:</p>
 * <ul>
 * <li>If there is a security policy, and the security policy denies the request, then the request is denied.</li>
 * <li>Otherwise, if the authorizers set is empty, the request is granted.</li>
 * <li>Otherwise, if no authorizer explicitly grants the operation, the request is denied.</li>
 * <li>Otherwise, if at least one authorizer explicitly grants the operation, and no authorizer explicitly denies the
 * operation, the request is granted.</li>
 * <li>Otherwise, if one authorizer explicitly denies the operation, remaining authorizers are not consulted, and the
 * request is denied.</li>
 * </ul>
 * <p>The order in which the authorizers are checked is not important.</p>
 * <p>Typically, authorizers are setup during the configuration of a channel:</p>
 * <pre>
 * BayeuxServer bayeuxServer = ...;
 * bayeuxServer.createIfAbsent("/television/cnn", new ConfigurableServerChannel.Initializer()
 * {
 *     public void configureChannel(ConfigurableServerChannel channel)
 *     {
 *         // Grant subscribe to all
 *         channel.addAuthorizer(GrantAuthorizer.GRANT_SUBSCRIBE);
 *
 *         // Grant publishes only to CNN employees
 *         channel.addAuthorizer(new Authorizer()
 *         {
 *             public Result authorize(Operation operation, ChannelId channel,
 *                                     ServerSession session, ServerMessage message)
 *             {
 *                 if (operation == Operation.PUBLISH &amp;&amp;
 *                         session.getAttribute("isCNNEmployee") == Boolean.TRUE)
 *                     return Result.grant();
 *                 else
 *                     return Result.ignore();
 *             }
 *         });
 *     }
 * });
 * </pre>
 * <p>A typical usage of authorizers is as follows:</p>
 * <ul>
 * <li>Create a wildcard authorizer that matches all channels and neither grants or
 * denies (e.g. use {@code org.cometd.server.authorizer.GrantAuthorizer.GRANT_NONE}).
 * This authorizer can be added to channel /** or to a more specific channel for your application such as
 * /game/**.
 * This ensures that authorizers set is not empty and that another authorizer must explicitly grant access.</li>
 * <li>For public channels, that all users can access, add authorizers that will simply grant
 * publish and/or subscribe permissions to the specific or wildcard channels.</li>
 * <li>For access controlled channels (e.g. only nominated players can publish to a game channel), then
 * specific implementation of authorizers need to be created that will check identities and possibly other
 * state before granting permission.
 * Typically there is no need for such authorizers to explicitly deny access, unless that attempted access
 * represents a specific error condition that needs to be passed to the client in the message associated
 * with a deny.</li>
 * <li>For cross cutting concerns, such as checking a users credit or implementing user bans, authorizers
 * can be created to explicitly deny access, without the need to modify all authorizers already in place
 * that may grant.</li>
 * </ul>
 *
 * @see SecurityPolicy
 */
public interface Authorizer {
    /**
     * Operations that are to be authorized on a channel
     */
    enum Operation {
        /**
         * The operation to create a channel that does not exist
         */
        CREATE,
        /**
         * The operation to subscribe to a channel to receive messages published to it
         */
        SUBSCRIBE,
        /**
         * The operation to publish messages to a channel
         */
        PUBLISH
    }

    /**
     * <p>Callback invoked to authorize the given {@code operation} on the given {@code channel}.</p>
     * <p>Additional parameters are passed to this method as context parameters, so that it is possible
     * to implement complex logic based on the {@link ServerSession} and {@link ServerMessage} that
     * are requesting the authorization.</p>
     * <p>Note that the message channel is not the same as the {@code channelId} parameter. For example,
     * for subscription requests, the message channel is {@link Channel#META_SUBSCRIBE}, while the
     * {@code channelId} parameter is the channel for which the subscription is requested.</p>
     * <p>Note that for {@link Operation#CREATE create operation}, the channel instance does not yet
     * exist: it will be created only after the authorization is granted.</p>
     *
     * @param operation the operation to authorize
     * @param channel   the channel for which the authorization has been requested
     * @param session   the session that is requesting the authorization
     * @param message   the message that triggered the authorization request
     * @return the result of the authorization
     */
    Result authorize(Operation operation, ChannelId channel, ServerSession session, ServerMessage message);

    /**
     * <p>The result of an authentication request.</p>
     */
    public static abstract class Result {
        /**
         * @param reason the reason for which the authorization is denied
         * @return a result that denies the authorization
         */
        public static Result deny(String reason) {
            return new Denied(reason);
        }

        /**
         * @return a result that grants the authorization
         */
        public static Result grant() {
            return Granted.GRANTED;
        }

        /**
         * @return a result that ignores the authorization, leaving the decision to other {@link Authorizer}s.
         */
        public static Result ignore() {
            return Ignored.IGNORED;
        }

        public boolean isDenied() {
            return false;
        }

        public boolean isGranted() {
            return false;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName().toLowerCase();
        }

        public static final class Denied extends Result {
            private final String reason;

            private Denied(String reason) {
                if (reason == null) {
                    reason = "";
                }
                this.reason = reason;
            }

            public String getReason() {
                return reason;
            }

            @Override
            public boolean isDenied() {
                return true;
            }

            @Override
            public String toString() {
                return super.toString() + " (reason='" + reason + "')";
            }
        }

        public static final class Granted extends Result {
            private static final Granted GRANTED = new Granted();

            private Granted() {
            }

            @Override
            public boolean isGranted() {
                return true;
            }
        }

        public static final class Ignored extends Result {
            private static final Ignored IGNORED = new Ignored();

            private Ignored() {
            }
        }
    }
}
