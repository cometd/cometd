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
package org.cometd.documentation.server;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.authorizer.GrantAuthorizer;

@SuppressWarnings("unused")
public class ServerAuthorizersDocs {
    public static void add(BayeuxServer bayeuxServer) {
        // tag::add[]
        bayeuxServer.createChannelIfAbsent("/my/channel",
                channel -> channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH));
        // end::add[]
    }

    interface DocsSecurityPolicy extends SecurityPolicy {
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise);

        // tag::methods[]
        public void canCreate(BayeuxServer server, ServerSession session, String channelId, ServerMessage message, Promise<Boolean> promise);

        public void canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message, Promise<Boolean> promise);

        public void canPublish(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message, Promise<Boolean> promise);
        // end::methods[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class Grant implements Authorizer {
        @Override
        // tag::grant[]
        public Result authorize(Operation operation, ChannelId channel, ServerSession session, ServerMessage message) {
            return Result.grant();
        }
        // end::grant[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class Ignore implements Authorizer {
        @Override
        // tag::ignore[]
        public Result authorize(Operation operation, ChannelId channel, ServerSession session, ServerMessage message) {
            return Result.ignore();
        }
        // end::ignore[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    public class Deny implements Authorizer {
        @Override
        // tag::deny[]
        public Result authorize(Operation operation, ChannelId channel, ServerSession session, ServerMessage message) {
            return Result.deny("reason_to_deny");
        }
        // end::deny[]
    }

    public static void example(BayeuxServer bayeuxServer, String gameId) {
        // tag::example1[]
        MarkedReference<ServerChannel> ref = bayeuxServer.createChannelIfAbsent("/game/**");
        ServerChannel gameStarStar = ref.getReference();
        gameStarStar.addAuthorizer(GrantAuthorizer.GRANT_NONE);
        // end::example1[]

        // tag::example2[]
        gameStarStar.addAuthorizer((operation, channel, session, message) -> {
            // Always grant authorization to local clients
            if (session.isLocalSession()) {
                return Authorizer.Result.grant();
            }

            boolean isCaptain = isCaptain(session);
            boolean isGameChannel = !channel.isWild() && new ChannelId("/game").isParentOf(channel);
            if (operation == Authorizer.Operation.CREATE && isCaptain && isGameChannel) {
                return Authorizer.Result.grant();
            }

            return Authorizer.Result.ignore();
        });
        // end::example2[]

        // tag::example3[]
        gameStarStar.addAuthorizer(GrantAuthorizer.GRANT_SUBSCRIBE);
        // end::example3[]

        // tag::example4[]
        ServerChannel gameChannel = bayeuxServer.getChannel("/game/" + gameId);
        gameChannel.addAuthorizer((operation, channel, session, message) -> {
            // Always grant authorization to local clients
            if (session.isLocalSession()) {
                return Authorizer.Result.grant();
            }

            boolean isPlayer = isPlayer(session, channel);
            if (operation == Authorizer.Operation.PUBLISH && isPlayer) {
                return Authorizer.Result.grant();
            }

            return Authorizer.Result.ignore();
        });
        // end::example4[]

        // tag::example5[]
        gameStarStar.addAuthorizer((operation, channel, session, message) -> {
            // Always grant authorization to local clients
            if (session.isLocalSession()) {
                return Authorizer.Result.grant();
            }

            boolean isCriminalSupporter = isCriminalSupporter(session);
            if (operation == Authorizer.Operation.SUBSCRIBE && isCriminalSupporter) {
                return Authorizer.Result.deny("criminal_supporter");
            }

            return Authorizer.Result.ignore();
        });
        // end::example5[]
    }

    private static boolean isCaptain(ServerSession session) {
        return false;
    }

    private static boolean isPlayer(ServerSession session, ChannelId channel) {
        return false;
    }

    private static boolean isCriminalSupporter(ServerSession session) {
        return false;
    }
}
