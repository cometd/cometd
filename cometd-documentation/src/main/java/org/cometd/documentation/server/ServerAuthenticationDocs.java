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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.DefaultSecurityPolicy;

@SuppressWarnings("unused")
public class ServerAuthenticationDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::config[]
    public class MyAppInitializer extends HttpServlet {
        @Override
        public void init() {
            BayeuxServer bayeux = (BayeuxServer)getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
            MyAppAuthenticator authenticator = new MyAppAuthenticator();
            bayeux.setSecurityPolicy(authenticator);
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException {
            throw new ServletException();
        }
    }
    // end::config[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::policy[]
    public class MyAppAuthenticator extends DefaultSecurityPolicy implements ServerSession.RemovedListener { // <1>
        @Override
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise) { // <2>
            if (session.isLocalSession()) { // <3>
                promise.succeed(true);
                return;
            }

            Map<String, Object> ext = message.getExt();
            if (ext == null) {
                promise.succeed(false);
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> authentication = (Map<String, Object>)ext.get("com.myapp.authn");
            if (authentication == null) {
                promise.succeed(false);
                return;
            }

            Object authenticationData = verify(authentication); // <4>
            if (authenticationData == null) {
                promise.succeed(false);
                return;
            }

            // Authentication successful

            // Link authentication data to the session <5>

            // Be notified when the session disappears
            session.addListener(this); // <6>

            promise.succeed(true);
        }

        @Override
        public void removed(ServerSession session, ServerMessage message, boolean expired) { // <7>
            // Unlink authentication data from the remote client <8>
        }
    }
    // end::policy[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::example[]
    public class UsersAuthenticator extends DefaultSecurityPolicy implements ServerSession.RemovedListener {
        private final Users users;

        public UsersAuthenticator(Users users) {
            this.users = users;
        }

        @Override
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise) {
            if (session.isLocalSession()) {
                promise.succeed(true);
                return;
            }

            Map<String, Object> ext = message.getExt();
            if (ext == null) {
                promise.succeed(false);
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> authentication = (Map<String, Object>)ext.get("com.myapp.authn");
            if (authentication == null) {
                promise.succeed(false);
                return;
            }

            if (!verify(authentication)) {
                promise.succeed(false);
                return;
            }

            // Authentication successful.

            // Link authentication data to the session.
            users.put(session, authentication);

            // Be notified when the session disappears.
            session.addListener(this);

            promise.succeed(true);
        }

        @Override
        public void removed(ServerSession session, ServerMessage message, boolean expired) {
            // Unlink authentication data from the remote client
            users.remove(session);
        }
    }
    // end::example[]

    private boolean verify(Map<String, Object> authentication) {
        return false;
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::users[]
    public class Users {
        private final ConcurrentMap<String, ServerSession> users = new ConcurrentHashMap<>();

        public void put(ServerSession session, Map<String, Object> credentials) {
            String user = (String)credentials.get("user");
            users.put(user, session);
        }

        public void remove(ServerSession session) {
            users.values().remove(session);
        }
    }
    // end::users[]

    class Listener {
        private BayeuxServer bayeuxServer = null;

        Listener() {
            // tag::listener[]
            Users users = new Users();
            bayeuxServer.addListener(new BayeuxServer.SessionListener() {
                @Override
                public void sessionAdded(ServerSession session, ServerMessage message) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> authentication = (Map<String, Object>)message.getExt().get("com.myapp.authn");
                    users.put(session, authentication);
                }

                @Override
                public void sessionRemoved(ServerSession session, ServerMessage message, boolean timeout) {
                    users.remove(session);
                }
            });
            // end::listener[]
        }
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::customReply[]
    public class MySecurityPolicy extends DefaultSecurityPolicy {
        @Override
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise) {
            canAuthenticate(session, message, Promise.from(authenticated -> {
                if (authenticated) {
                    promise.succeed(true);
                } else {
                    // Retrieve the handshake response.
                    ServerMessage.Mutable handshakeReply = message.getAssociated();

                    // Modify the advice, in this case tell to try again
                    // If the advice is not modified it will default to disconnect the client.
                    Map<String, Object> advice = handshakeReply.getAdvice(true);
                    advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);

                    // Modify the ext field with extra information on the authentication failure
                    Map<String, Object> ext = handshakeReply.getExt(true);
                    Map<String, Object> authentication = new HashMap<>();
                    ext.put("com.myapp.authn", authentication);
                    authentication.put("failureReason", "invalid_credentials");

                    promise.succeed(false);
                }
            }, promise::fail));
        }
    }
    // end::customReply[]

    private void canAuthenticate(ServerSession session, ServerMessage message, Promise<Boolean> promise) {
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::customReplyExt[]
    public class HandshakeExtension implements BayeuxServer.Extension {
        @Override
        public void outgoing(ServerSession from, ServerSession to, ServerMessage.Mutable message, Promise<Boolean> promise) {
            if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                if (!message.isSuccessful()) {
                    Map<String, Object> advice = message.getAdvice(true);
                    advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);

                    Map<String, Object> ext = message.getExt(true);
                    Map<String, Object> authentication = new HashMap<>();
                    ext.put("com.myapp.authn", authentication);
                    authentication.put("failureReason", "invalid_credentials");
                }
            }
            promise.succeed(true);
        }
    }
    // end::customReplyExt[]
}
