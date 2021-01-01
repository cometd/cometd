/*
 * Copyright (c) 2008-2021 the original author or authors.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.cometd.annotation.Listener;
import org.cometd.annotation.Param;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.server.Configure;
import org.cometd.annotation.server.RemoteCall;
import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.bayeux.BinaryData;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.authorizer.GrantAuthorizer;

@SuppressWarnings("unused")
public class ServerServiceDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::inherited[]
    public class EchoService extends AbstractService { // <1>
        public EchoService(BayeuxServer bayeuxServer) { // <2>
            super(bayeuxServer, "echo"); // <3>
            addService("/echo", "processEcho"); // <4>
        }

        public void processEcho(ServerSession session, ServerMessage message) { // <5>
            session.deliver(getServerSession(), "/echo", message.getData(), Promise.noop()); // <6>
        }
    }
    // end::inherited[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::wildcard[]
    public class BaseballTeamService extends AbstractService {
        public BaseballTeamService(BayeuxServer bayeux) {
            super(bayeux, "baseballTeam");
            // Add a service for a wildcard channel.
            addService("/baseball/team/*", "processBaseballTeam");
        }

        public void processBaseballTeam(ServerSession session, ServerMessage message) {
            // Upon receiving a message on channel /baseball/team/*,
            // forward it to channel /events/baseball/team/*.
            getBayeux().getChannel("/events" + message.getChannel()).publish(getServerSession(), message.getData(), Promise.noop());
        }
    }
    // end::wildcard[]

    interface GameCommand {
        Type getType();

        String getGameId();

        enum Type {
            GAME_BEGIN, GAME_END
        }
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::dynamic[]
    public class GameService extends AbstractService {
        public GameService(BayeuxServer bayeux) {
            super(bayeux, "game");
            addService("/service/game/*", "processGameCommand");
        }

        public void processGameCommand(ServerSession remote, ServerMessage message) {
            GameCommand command = (GameCommand)message.getData();
            switch (command.getType()) {
                case GAME_BEGIN: {
                    // Add a service method dynamically.
                    addService("/game/" + command.getGameId(), "processGame");
                    break;
                }
                case GAME_END: {
                    // Remove a service method dynamically.
                    removeService("/game/" + command.getGameId());
                    break;
                }
                // Other cases here.
            }
        }

        public void processGame(ServerSession remote, ServerMessage message) {
            // Process a game.
        }
    }
    // end::dynamic[]


    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedService[]
    @org.cometd.annotation.Service("echoService")
    public class AnnotatedEchoService {
        @javax.inject.Inject
        private BayeuxServer bayeux;
    }
    // end::annotatedService[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::inheritedService[]
    public class InheritedEchoService extends AbstractService {
        public InheritedEchoService(BayeuxServer bayeux) {
            super(bayeux, "echoService");
        }
    }
    // end::inheritedService[]


    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedConfigure[]
    @Service("echoService")
    public class ServiceWithAnnotatedChannel {
        @Inject
        private BayeuxServer bayeux;

        @Configure("/echo")
        public void configure(ConfigurableServerChannel channel) {
            channel.setLazy(true);
            channel.addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);
        }
    }
    // end::annotatedConfigure[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedSession[]
    @Service("echoService")
    public class ServiceWithAnnotatedSession {
        @Inject
        private BayeuxServer bayeux;

        @org.cometd.annotation.Session
        private LocalSession localSession;

        @org.cometd.annotation.Session
        private ServerSession serverSession;
    }
    // end::annotatedSession[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedServiceMethod[]
    @Service("echoService")
    public class AnnotatedServiceWithMethod {
        @Inject
        private BayeuxServer bayeux;
        @Session
        private ServerSession serverSession;

        @org.cometd.annotation.Listener("/echo")
        public void echo(ServerSession remote, ServerMessage.Mutable message) {
            String channel = message.getChannel();
            Object data = message.getData();
            remote.deliver(serverSession, channel, data, Promise.noop());
        }
    }
    // end::annotatedServiceMethod[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::inheritedServiceMethod[]
    @Service("echoService")
    public class InheritedServiceWithMethod extends AbstractService {
        public InheritedServiceWithMethod(BayeuxServer bayeux) {
            super(bayeux, "echoService");
            addService("/echo", "echo");
        }

        public void echo(ServerSession remote, ServerMessage.Mutable message) {
            String channel = message.getChannel();
            Object data = message.getData();
            remote.deliver(getServerSession(), channel, data, Promise.noop());
        }
    }
    // end::inheritedServiceMethod[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedServiceWithParams[]
    @Service
    public class ParametrizedService {
        @Listener("/news/{category}/{event}")
        public void serviceNews(ServerSession remote, ServerMessage message, @Param("category") String category, @Param("event") String event) {
            // Process the event.
        }
    }
    // end::annotatedServiceWithParams[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedSubscriptionMethod[]
    @Service("echoService")
    public class ServiceWithSubscriptionMethod {
        @Inject
        private BayeuxServer bayeux;
        @Session
        private ServerSession serverSession;

        @org.cometd.annotation.Subscription("/echo")
        public void echo(Message message) {
            System.out.println("Echo service published " + message);
        }
    }
    // end::annotatedSubscriptionMethod[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedServiceBinary[]
    @Service("uploadService")
    public class UploadService {
        @Inject
        private BayeuxServer bayeux;
        @Session
        private ServerSession serverSession;

        @Listener("/binary")
        public void upload(ServerSession remote, ServerMessage message) throws IOException {
            BinaryData binary = (BinaryData)message.getData();

            Map<String, Object> meta = binary.getMetaData();
            String fileName = (String)meta.get("fileName");
            Path path = Paths.get(System.getProperty("java.io.tmpdir"), fileName);

            ByteBuffer buffer = binary.asByteBuffer();

            try (ByteChannel channel = Files.newByteChannel(path, StandardOpenOption.APPEND)) {
                channel.write(buffer);
            }

            if (binary.isLast()) {
                // Do something with the whole file.
            }
        }
    }
    // end::annotatedServiceBinary[]

    private static List<String> retrieveContactsFromDatabase(String userId) {
        return Collections.emptyList();
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::annotatedServiceRemoteCall[]
    @Service
    public class RemoteCallService {
        @RemoteCall("contacts")
        public void retrieveContacts(RemoteCall.Caller caller, Object data) {
            // Perform a lengthy query to the database to
            // retrieve the contacts in a separate thread.
            new Thread(() -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> arguments = (Map<String, Object>)data;
                    String userId = (String)arguments.get("userId");
                    List<String> contacts = retrieveContactsFromDatabase(userId);

                    // We got the contacts, respond.
                    caller.result(contacts);
                } catch (Exception x) {
                    // Uh-oh, something went wrong.
                    caller.failure(x.getMessage());
                }
            }).start();
        }
    }
    // end::annotatedServiceRemoteCall[]

    public void annotationProcessing() {
        BayeuxServer bayeuxServer = new BayeuxServerImpl();

        // tag::annotationProcessing[]
        // Create the ServerAnnotationProcessor.
        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeuxServer);

        // Create the service instance.
        AnnotatedEchoService service = new AnnotatedEchoService();

        // Process the annotated service.
        processor.process(service);
        // end::annotationProcessing[]
    }
}
