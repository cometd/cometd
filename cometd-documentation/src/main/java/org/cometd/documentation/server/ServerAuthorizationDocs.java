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
package org.cometd.documentation.server;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.DefaultSecurityPolicy;

@SuppressWarnings("unused")
public class ServerAuthorizationDocs {
    interface DocsSecurityPolicy extends SecurityPolicy {
        // tag::methods[]
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise);

        public void canCreate(BayeuxServer server, ServerSession session, String channelId, ServerMessage message, Promise<Boolean> promise);

        public void canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message, Promise<Boolean> promise);

        public void canPublish(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message, Promise<Boolean> promise);
        // end::methods[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::custom[]
    public class CustomSecurityPolicy extends DefaultSecurityPolicy {
        @Override
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise) {
            // Always allow local clients
            if (session.isLocalSession()) {
                promise.succeed(true);
                return;
            }

            // Implement the custom authentication logic, completing the
            // Promise with the (possibly asynchronous) authentication result.
            authenticate(server, session, message, promise);
        }
    }
    // end::custom[]

    private void authenticate(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise) {
    }
}
