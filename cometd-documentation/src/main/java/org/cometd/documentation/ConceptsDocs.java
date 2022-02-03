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
package org.cometd.documentation;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import jakarta.inject.Inject;
import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.DefaultSecurityPolicy;
import org.eclipse.jetty.client.HttpClient;

@SuppressWarnings("unused")
public class ConceptsDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::threading[]
    @Service
    public class MyService {
        @Inject
        private BayeuxServer bayeuxServer;
        @Session
        private LocalSession localSession;

        @Listener("/service/query")
        public void processQuery(ServerSession remoteSession, ServerMessage message) {
            new Thread(() -> {
                Map<String, Object> data = performTimeConsumingTask(message);

                // Send data to client once the time consuming task is finished
                remoteSession.deliver(localSession, message.getChannel(), data, Promise.noop());
            }).start();
        }
    }
    // end::threading[]

    private Map<String, Object> performTimeConsumingTask(ServerMessage message) {
        return null;
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::policy[]
    public class MySecurityPolicy extends DefaultSecurityPolicy {
        @Override
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise) {
            boolean authenticated = authenticate(session, message);

            if (!authenticated) {
                ServerMessage.Mutable reply = message.getAssociated();
                // Here you can customize the reply
            }

            promise.succeed(authenticated);
        }
    }
    // end::policy[]

    private boolean authenticate(ServerSession session, ServerMessage message) {
        return false;
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::asyncPolicy[]
    public class AsyncSecurityPolicy extends DefaultSecurityPolicy {
        @Override
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise) {
            CompletableFuture.supplyAsync(() -> authenticate(session, message))
                    .whenComplete(promise.complete());
        }
    }
    // end::asyncPolicy[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::httpPolicy[]
    public class HttpSecurityPolicy extends DefaultSecurityPolicy {
        private final HttpClient httpClient;

        public HttpSecurityPolicy(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise) {
            httpClient.newRequest("https://authHost")
                    .param("user", (String)message.get("user"))
                    .send(result -> {
                        if (result.isSucceeded()) {
                            if (result.getResponse().getStatus() == 200) {
                                promise.succeed(true);
                            } else {
                                ServerMessage.Mutable reply = message.getAssociated();
                                // Here you can customize the handshake reply to
                                // specify why the authentication was unsuccessful.
                                promise.succeed(false);
                            }
                        } else {
                            promise.fail(result.getFailure());
                        }
                    });
        }
    }
    // end::httpPolicy[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::privateMessage[]
    @Service
    public class PrivateMessageService {
        @Session
        private ServerSession session;

        @Listener("/service/private")
        public void handlePrivateMessage(ServerSession sender, ServerMessage message) {
            // Retrieve the userId from the message
            String userId = (String)message.get("targetUserId");

            // Use the mapping established during handshake to
            // retrieve the ServerSession for a given userId
            ServerSession recipient = findServerSessionFromUserId(userId);

            // Deliver the message to the other peer
            recipient.deliver(session, message.getChannel(), message.getData(), Promise.noop());
        }
    }
    // end::privateMessage[]

    private ServerSession findServerSessionFromUserId(String userId) {
        return null;
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::broadcaster[]
    public class ExternalEventBroadcaster {
        private final BayeuxServer bayeuxServer;
        private final LocalSession session;

        public ExternalEventBroadcaster(BayeuxServer bayeuxServer) {
            this.bayeuxServer = bayeuxServer;

            // Create a local session that will act as the "sender"
            this.session = bayeuxServer.newLocalSession("external");
            this.session.handshake();
        }

        public void onExternalEvent(ExternalEvent event) {
            // Retrieve the channel to broadcast to, for example
            // based on the "type" property of the external event
            ServerChannel channel = this.bayeuxServer.getChannel("/events/" + event.getType());
            if (channel != null) {
                // Create the data to broadcast by converting the external event
                Map<String, Object> data = convertExternalEvent(event);

                // Broadcast the data
                channel.publish(this.session, data, Promise.noop());
            }
        }
    }
    // end::broadcaster[]

    private Map<String, Object> convertExternalEvent(ExternalEvent event) {
        return null;
    }

    private static class ExternalEvent {
        public String getType() {
            return null;
        }
    }
}
