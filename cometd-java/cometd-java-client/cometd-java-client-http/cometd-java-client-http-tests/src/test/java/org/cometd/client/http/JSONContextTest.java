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
package org.cometd.client.http;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.HashMapMessage;
import org.cometd.common.JSONContext;
import org.cometd.common.JacksonJSONContextClient;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.JSONContextServer;
import org.cometd.server.JacksonJSONContextServer;
import org.cometd.server.JettyJSONContextServer;
import org.cometd.server.ServerMessageImpl;
import org.cometd.server.http.AsyncJSONTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class JSONContextTest extends ClientServerTest {
    public static Stream<Arguments> jsonContexts() {
        return Stream.of(
                Arguments.of(JacksonJSONContextServer.class, JacksonJSONContextClient.class),
                Arguments.of(JettyJSONContextServer.class, JettyJSONContextClient.class)
        );
    }

    @ParameterizedTest(name = "{index}: JSON Context Server: {0} JSON Context Client: {1}")
    @MethodSource("jsonContexts")
    public void testAsyncParser(Class<JSONContextServer> jsonContextServerClass, Class<JSONContext.Client> jsonContextClientClass) throws Exception {
        JSONContext.Client jsonContextClient = jsonContextClientClass.getConstructor().newInstance();
        JSONContext.AsyncParser clientParser = jsonContextClient.newAsyncParser();
        Assumptions.assumeTrue(clientParser != null);

        JSONContextServer jsonContextServer = jsonContextServerClass.getConstructor().newInstance();
        JSONContext.AsyncParser serverParser = jsonContextServer.newAsyncParser();
        Assumptions.assumeTrue(serverParser != null);

        String json = "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"id\": \"1\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]," +
                "\"advice\": {\"timeout\": 0, \"reconnect\": \"retry\"}," +
                "\"ext\": {\"ack\": true}" +
                "}]";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < bytes.length; ++i) {
            clientParser.parse(bytes, i, 1);
            serverParser.parse(bytes, i, 1);
        }
        List<Message.Mutable> clientMessages = clientParser.complete();
        List<ServerMessage.Mutable> serverMessages = serverParser.complete();

        Assertions.assertEquals(1, clientMessages.size());
        Message.Mutable clientMessage = clientMessages.get(0);
        Assertions.assertTrue(clientMessage instanceof HashMapMessage);
        Assertions.assertEquals(1, serverMessages.size());
        ServerMessage.Mutable serverMessage = serverMessages.get(0);
        Assertions.assertTrue(serverMessage instanceof ServerMessageImpl);
    }

    @Test
    public void testHandshakeMessageNoArray() throws Exception {
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put(BayeuxServerImpl.TRANSPORTS_OPTION, AsyncJSONTransport.class.getName());
        // Only Jetty supports the lenient parsing tested here.
        serverOptions.put(AbstractServerTransport.JSON_CONTEXT_OPTION, JettyJSONContextServer.class.getName());
        start(serverOptions);

        // Do no wrap the handshake into an array.
        String handshake = "{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"id\": \"1\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]," +
                "\"advice\": {\"timeout\": 0, \"reconnect\": \"retry\"}," +
                "\"ext\": {\"ack\": true}" +
                "}";

        ContentResponse response = httpClient.newRequest(cometdURL)
                .method(HttpMethod.POST)
                .content(new StringContentProvider(handshake))
                .send();

        Assertions.assertEquals(HttpStatus.OK_200, response.getStatus());
        String json = response.getContentAsString();
        JSONContext.Client jsonContextClient = new JettyJSONContextClient();
        JSONContext.AsyncParser asyncParser = jsonContextClient.newAsyncParser();
        asyncParser.parse(StandardCharsets.UTF_8.encode(json));
        List<Message.Mutable> messages = asyncParser.complete();
        Assertions.assertEquals(1, messages.size());
    }

    @ParameterizedTest(name = "{index}: JSON Context Server: {0} JSON Context Client: {1}")
    @MethodSource("jsonContexts")
    public void testHandshakeSubscribePublishUnsubscribeDisconnect(Class<JSONContextServer> jsonContextServerClass, Class<JSONContext.Client> jsonContextClientClass) throws Exception {
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put(AbstractServerTransport.JSON_CONTEXT_OPTION, jsonContextServerClass.getName());
        start(serverOptions);

        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put(ClientTransport.JSON_CONTEXT_OPTION, jsonContextClientClass.getName());
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(clientOptions, httpClient));

        BlockingQueue<Message> metaMessages = new ArrayBlockingQueue<>(16);
        client.getChannel("/meta/*").addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            // Skip /meta/connect messages because they arrive without notice
            // and most likely fail the test that it is waiting for other messages
            if (!Channel.META_CONNECT.equals(message.getChannel())) {
                metaMessages.offer(message);
            }
        });

        client.handshake();

        Message message = metaMessages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        Assertions.assertTrue(message.isSuccessful());
        String id = client.getId();
        Assertions.assertNotNull(id);

        BlockingQueue<Message> messages = new ArrayBlockingQueue<>(16);
        ClientSessionChannel.MessageListener subscriber = (c, m) -> messages.offer(m);
        ClientSessionChannel aChannel = client.getChannel("/a/channel");
        aChannel.subscribe(subscriber);

        message = metaMessages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_SUBSCRIBE, message.getChannel());
        Assertions.assertTrue(message.isSuccessful());

        String data = "data";
        aChannel.publish(data);
        message = messages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(data, message.getData());

        aChannel.unsubscribe(subscriber);
        message = metaMessages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_UNSUBSCRIBE, message.getChannel());
        Assertions.assertTrue(message.isSuccessful());

        disconnectBayeuxClient(client);
    }
}
