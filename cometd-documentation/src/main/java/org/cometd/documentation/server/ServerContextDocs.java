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

import java.util.HashSet;
import java.util.Set;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.DefaultSecurityPolicy;

@SuppressWarnings("unused")
public class ServerContextDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::policy[]
    public class OneClientPerAddressSecurityPolicy extends DefaultSecurityPolicy {
        private final Set<String> addresses = new HashSet<>();

        @Override
        public void canHandshake(BayeuxServer bayeuxServer, ServerSession session, ServerMessage message, Promise<Boolean> promise) {
            BayeuxContext context = message.getBayeuxContext();

            // Get the remote address of the client.
            String remoteAddress = context.getRemoteAddress().getHostString();

            // Only allow clients from different remote addresses.

            boolean notPresent = addresses.add(remoteAddress);

            // Avoid to leak addresses.
            session.addListener((ServerSession.RemovedListener)(s, m, t) -> addresses.remove(remoteAddress));

            promise.succeed(notPresent);
        }
    }
    // end::policy[]
}
