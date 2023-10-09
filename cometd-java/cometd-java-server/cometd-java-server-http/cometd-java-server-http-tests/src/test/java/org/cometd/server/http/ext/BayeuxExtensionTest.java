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
package org.cometd.server.http.ext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.JSONContextServer;
import org.cometd.server.JettyJSONContextServer;
import org.cometd.server.http.AbstractBayeuxClientServerTest;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class BayeuxExtensionTest extends AbstractBayeuxClientServerTest {
    private static final String CLIENT_MESSAGE_FIELD = "clientMessageField";
    private static final String CLIENT_EXT_FIELD = "clientExtField";
    private static final String CLIENT_DATA_FIELD = "clientDataField";
    private static final String SERVER_EXT_MESSAGE_FIELD = "serverExtMessageField";
    private static final String SERVER_EXT_DATA_FIELD = "serverExtDataField";
    private static final String SERVER_EXT_EXT_FIELD = "serverExtExtField";
    private static final String SERVER_MESSAGE_FIELD = "serverMessageField";
    private static final String SERVER_DATA_FIELD = "serverDataField";
    private static final String SERVER_EXT_FIELD = "serverExtField";
    private static final String SERVER_EXT_INFO = "fromExtension";
    private static final String CLIENT_INFO = "fromClient";
    private static final String SERVICE_INFO = "fromService";

    @ParameterizedTest
    @MethodSource("transports")
    public void testBayeuxExtensionOnHandshake(Transport transport) throws Exception {
        startServer(transport, null);

        bayeux.addExtension(new MetaExtension());
        AtomicReference<ServerMessage> handshakeRef = new AtomicReference<>();
        new MetaHandshakeService(bayeux, handshakeRef);

        Request handshake = newBayeuxRequest("" +
                                             "[{" +
                                             "\"channel\": \"/meta/handshake\"," +
                                             "\"version\": \"1.0\"," +
                                             "\"minimumVersion\": \"1.0\"," +
                                             "\"supportedConnectionTypes\": [\"long-polling\"]," +
                                             "\"" + CLIENT_MESSAGE_FIELD + "\": \"" + CLIENT_INFO + "\"," +
                                             "\"ext\": { \"" + CLIENT_EXT_FIELD + "\": \"" + CLIENT_INFO + "\" }," +
                                             "\"data\": { \"" + CLIENT_DATA_FIELD + "\": \"" + CLIENT_INFO + "\" }" +
                                             "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertEquals(SERVER_EXT_INFO, handshakeRef.get().get(SERVER_EXT_MESSAGE_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, handshakeRef.get().getDataAsMap().get(SERVER_EXT_DATA_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, handshakeRef.get().getExt().get(SERVER_EXT_EXT_FIELD));
        Assertions.assertEquals(CLIENT_INFO, handshakeRef.get().get(CLIENT_MESSAGE_FIELD));
        Assertions.assertEquals(CLIENT_INFO, handshakeRef.get().getExt().get(CLIENT_EXT_FIELD));
        Assertions.assertEquals(CLIENT_INFO, handshakeRef.get().getDataAsMap().get(CLIENT_DATA_FIELD));

        JSONContextServer jsonContext = new JettyJSONContextServer();
        ServerMessage.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(1, messages.length);
        Map<String, Object> message = messages[0];
        Assertions.assertEquals(SERVER_EXT_INFO, message.get(SERVER_EXT_MESSAGE_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.DATA_FIELD)).get(SERVER_EXT_DATA_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.EXT_FIELD)).get(SERVER_EXT_EXT_FIELD));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testBayeuxExtensionOnServiceChannel(Transport transport) throws Exception {
        startServer(transport, null);

        String channel = "/service/test";
        bayeux.addExtension(new NonMetaExtension(channel));
        AtomicReference<ServerMessage> publishRef = new AtomicReference<>();
        new ServiceChannelService(bayeux, channel, publishRef);

        Request handshake = newBayeuxRequest("" +
                                             "[{" +
                                             "\"channel\": \"/meta/handshake\"," +
                                             "\"version\": \"1.0\"," +
                                             "\"minimumVersion\": \"1.0\"," +
                                             "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                             "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish = newBayeuxRequest("" +
                                           "[{" +
                                           "\"clientId\": \"" + clientId + "\"," +
                                           "\"channel\": \"" + channel + "\"," +
                                           "\"" + CLIENT_MESSAGE_FIELD + "\": \"" + CLIENT_INFO + "\"," +
                                           "\"ext\": { \"" + CLIENT_EXT_FIELD + "\": \"" + CLIENT_INFO + "\" }," +
                                           "\"data\": { \"" + CLIENT_DATA_FIELD + "\": \"" + CLIENT_INFO + "\" }" +
                                           "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertEquals(SERVER_EXT_INFO, publishRef.get().get(SERVER_EXT_MESSAGE_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, publishRef.get().getDataAsMap().get(SERVER_EXT_DATA_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, publishRef.get().getExt().get(SERVER_EXT_EXT_FIELD));
        Assertions.assertEquals(CLIENT_INFO, publishRef.get().get(CLIENT_MESSAGE_FIELD));
        Assertions.assertEquals(CLIENT_INFO, publishRef.get().getExt().get(CLIENT_EXT_FIELD));
        Assertions.assertEquals(CLIENT_INFO, publishRef.get().getDataAsMap().get(CLIENT_DATA_FIELD));

        JSONContextServer jsonContext = new JettyJSONContextServer();
        ServerMessage.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(2, messages.length);
        Map<String, Object> message = messages[0].containsKey(Message.DATA_FIELD) ? messages[0] : messages[1];
        Assertions.assertEquals(SERVER_EXT_INFO, message.get(SERVER_EXT_MESSAGE_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.DATA_FIELD)).get(SERVER_EXT_DATA_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.EXT_FIELD)).get(SERVER_EXT_EXT_FIELD));
        Assertions.assertEquals(SERVICE_INFO, message.get(SERVER_MESSAGE_FIELD));
        Assertions.assertEquals(SERVICE_INFO, ((Map)message.get(Message.DATA_FIELD)).get(SERVER_DATA_FIELD));
        Assertions.assertEquals(SERVICE_INFO, ((Map)message.get(Message.EXT_FIELD)).get(SERVER_EXT_FIELD));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testBayeuxExtensionOnBroadcastChannel(Transport transport) throws Exception {
        startServer(transport, null);

        String channel = "/test";
        bayeux.addExtension(new NonMetaExtension(channel));
        AtomicReference<ServerMessage> publishRef = new AtomicReference<>();
        new BroadcastChannelService(bayeux, channel, publishRef);

        Request handshake = newBayeuxRequest("" +
                                             "[{" +
                                             "\"channel\": \"/meta/handshake\"," +
                                             "\"version\": \"1.0\"," +
                                             "\"minimumVersion\": \"1.0\"," +
                                             "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                             "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request subscribe = newBayeuxRequest("" +
                                             "[{" +
                                             "\"clientId\": \"" + clientId + "\"," +
                                             "\"channel\": \"/meta/subscribe\"," +
                                             "\"subscription\": \"" + channel + "\"," +
                                             "}]");
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());

        Request publish = newBayeuxRequest("" +
                                           "[{" +
                                           "\"clientId\": \"" + clientId + "\"," +
                                           "\"channel\": \"" + channel + "\"," +
                                           "\"" + CLIENT_MESSAGE_FIELD + "\": \"" + CLIENT_INFO + "\"," +
                                           "\"ext\": { \"" + CLIENT_EXT_FIELD + "\": \"" + CLIENT_INFO + "\" }," +
                                           "\"data\": { \"" + CLIENT_DATA_FIELD + "\": \"" + CLIENT_INFO + "\" }" +
                                           "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertEquals(SERVER_EXT_INFO, publishRef.get().get(SERVER_EXT_MESSAGE_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, publishRef.get().getDataAsMap().get(SERVER_EXT_DATA_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, publishRef.get().getExt().get(SERVER_EXT_EXT_FIELD));
        Assertions.assertEquals(CLIENT_INFO, publishRef.get().get(CLIENT_MESSAGE_FIELD));
        Assertions.assertEquals(CLIENT_INFO, publishRef.get().getExt().get(CLIENT_EXT_FIELD));
        Assertions.assertEquals(CLIENT_INFO, publishRef.get().getDataAsMap().get(CLIENT_DATA_FIELD));

        JSONContextServer jsonContext = new JettyJSONContextServer();
        ServerMessage.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Assertions.assertEquals(2, messages.length);
        Map<String, Object> message = messages[0].containsKey(Message.DATA_FIELD) ? messages[0] : messages[1];
        Assertions.assertEquals(SERVER_EXT_INFO, message.get(SERVER_EXT_MESSAGE_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.DATA_FIELD)).get(SERVER_EXT_DATA_FIELD));
        Assertions.assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.EXT_FIELD)).get(SERVER_EXT_EXT_FIELD));
        Assertions.assertEquals(SERVICE_INFO, message.get(SERVER_MESSAGE_FIELD));
        Assertions.assertEquals(SERVICE_INFO, ((Map)message.get(Message.DATA_FIELD)).get(SERVER_DATA_FIELD));
        Assertions.assertEquals(SERVICE_INFO, ((Map)message.get(Message.EXT_FIELD)).get(SERVER_EXT_FIELD));
    }

    private static class MetaExtension implements BayeuxServer.Extension {
        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            if (Channel.META_HANDSHAKE.equals(message.getChannel()) && (from == null || !from.isLocalSession())) {
                message.put(SERVER_EXT_MESSAGE_FIELD, SERVER_EXT_INFO);
                message.getDataAsMap(true).put(SERVER_EXT_DATA_FIELD, SERVER_EXT_INFO);
                message.getExt(true).put(SERVER_EXT_EXT_FIELD, SERVER_EXT_INFO);
            }
            return true;
        }

        @Override
        public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
            if (Channel.META_HANDSHAKE.equals(message.getChannel()) && to != null && !to.isLocalSession()) {
                message.put(SERVER_EXT_MESSAGE_FIELD, SERVER_EXT_INFO);
                message.getDataAsMap(true).put(SERVER_EXT_DATA_FIELD, SERVER_EXT_INFO);
                message.getExt(true).put(SERVER_EXT_EXT_FIELD, SERVER_EXT_INFO);
            }
            return true;
        }
    }

    private static class NonMetaExtension implements BayeuxServer.Extension {
        private final String channel;

        private NonMetaExtension(String channel) {
            this.channel = channel;
        }

        @Override
        public boolean rcv(ServerSession from, ServerMessage.Mutable message) {
            if (from != null && !from.isLocalSession() && channel.equals(message.getChannel())) {
                message.put(SERVER_EXT_MESSAGE_FIELD, SERVER_EXT_INFO);
                message.getDataAsMap(true).put(SERVER_EXT_DATA_FIELD, SERVER_EXT_INFO);
                message.getExt(true).put(SERVER_EXT_EXT_FIELD, SERVER_EXT_INFO);
            }
            return true;
        }

        @Override
        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message) {
            if (from != null && from.isLocalSession() && to != null && !to.isLocalSession()) {
                message.put(SERVER_EXT_MESSAGE_FIELD, SERVER_EXT_INFO);
                message.getDataAsMap(true).put(SERVER_EXT_DATA_FIELD, SERVER_EXT_INFO);
                message.getExt(true).put(SERVER_EXT_EXT_FIELD, SERVER_EXT_INFO);
            }
            return true;
        }
    }

    public static class MetaHandshakeService extends AbstractService {
        private final AtomicReference<ServerMessage> handshakeRef;

        public MetaHandshakeService(BayeuxServer bayeux, AtomicReference<ServerMessage> handshakeRef) {
            super(bayeux, "test");
            this.handshakeRef = handshakeRef;
            addService(Channel.META_HANDSHAKE, "metaHandshake");
        }

        public void metaHandshake(ServerSession remote, ServerMessage message) {
            handshakeRef.set(message);
        }
    }

    public static class ServiceChannelService extends AbstractService {
        private final AtomicReference<ServerMessage> publishRef;

        public ServiceChannelService(BayeuxServer bayeux, String channel, AtomicReference<ServerMessage> publishRef) {
            super(bayeux, "test");
            this.publishRef = publishRef;
            addService(channel, "test");
        }

        public void test(ServerSession remote, ServerMessage message) {
            publishRef.set(message);

            ServerMessage.Mutable response = getBayeux().newMessage();
            response.put(SERVER_MESSAGE_FIELD, SERVICE_INFO);
            response.getDataAsMap(true).put(SERVER_DATA_FIELD, SERVICE_INFO);
            response.getExt(true).put(SERVER_EXT_FIELD, SERVICE_INFO);
            remote.deliver(getServerSession(), response, Promise.noop());
        }
    }

    public static class BroadcastChannelService extends AbstractService {
        private final AtomicReference<ServerMessage> publishRef;

        public BroadcastChannelService(BayeuxServerImpl bayeux, String channel, AtomicReference<ServerMessage> publishRef) {
            super(bayeux, "test");
            this.publishRef = publishRef;
            addService(channel, "test");
        }

        public void test(ServerSession remote, ServerMessage.Mutable message) {
            message.put(SERVER_MESSAGE_FIELD, SERVICE_INFO);
            message.getDataAsMap(true).put(SERVER_DATA_FIELD, SERVICE_INFO);
            message.getExt(true).put(SERVER_EXT_FIELD, SERVICE_INFO);
            publishRef.set(message);
        }
    }
}
