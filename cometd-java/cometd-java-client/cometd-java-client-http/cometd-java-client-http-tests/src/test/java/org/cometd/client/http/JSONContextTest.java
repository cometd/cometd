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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

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
import org.cometd.server.JSONContextServer;
import org.cometd.server.JacksonJSONContextServer;
import org.cometd.server.JettyJSONContextServer;
import org.cometd.server.ServerMessageImpl;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class JSONContextTest extends ClientServerTest {
    @Parameterized.Parameters(name = "{index}: JSON Context Server: {0} JSON Context Client: {1}")
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][]
                {
                        {JacksonJSONContextServer.class, JacksonJSONContextClient.class},
                        {JettyJSONContextServer.class, JettyJSONContextClient.class}
                }
        );
    }

    private final Class<?> jsonContextServerClass;
    private final Class<?> jsonContextClientClass;

    public JSONContextTest(Class<?> jsonContextServerClass, Class<?> jsonContextClientClass) {
        this.jsonContextServerClass = jsonContextServerClass;
        this.jsonContextClientClass = jsonContextClientClass;
    }

    @Test
    public void testAsyncParser() throws Exception {
        JSONContext.Client jsonContextClient = (JSONContext.Client)jsonContextClientClass.getConstructor().newInstance();
        JSONContext.AsyncParser clientParser = jsonContextClient.newAsyncParser();
        Assume.assumeNotNull(clientParser);

        JSONContextServer jsonContextServer = (JSONContextServer)jsonContextServerClass.getConstructor().newInstance();
        JSONContext.AsyncParser serverParser = jsonContextServer.newAsyncParser();
        Assume.assumeNotNull(serverParser);

        String json = "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"id\": \"1\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]," +
                "\"advice\": {\"timeout\": 0, \"reconnect\": \"retry\"}," +
                "\"ext\": {\"ack\": true}" +
                "}]";
        byte[] bytes = json.getBytes(UTF_8);

        for (int i = 0; i < bytes.length; ++i) {
            clientParser.parse(bytes, i, 1);
            serverParser.parse(bytes, i, 1);
        }
        List<Message.Mutable> clientMessages = clientParser.complete();
        List<ServerMessage.Mutable> serverMessages = serverParser.complete();

        assertEquals(1, clientMessages.size());
        Message.Mutable clientMessage = clientMessages.get(0);
        assertTrue(clientMessage instanceof HashMapMessage);
        assertEquals(1, serverMessages.size());
        ServerMessage.Mutable serverMessage = serverMessages.get(0);
        assertTrue(serverMessage instanceof ServerMessageImpl);
    }

    @Test
    public void testHandshakeSubscribePublishUnsubscribeDisconnect() throws Exception {
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
        Assert.assertNotNull(message);
        assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        Assert.assertTrue(message.isSuccessful());
        String id = client.getId();
        Assert.assertNotNull(id);

        BlockingQueue<Message> messages = new ArrayBlockingQueue<>(16);
        ClientSessionChannel.MessageListener subscriber = (c, m) -> messages.offer(m);
        ClientSessionChannel aChannel = client.getChannel("/a/channel");
        aChannel.subscribe(subscriber);

        message = metaMessages.poll(5, TimeUnit.SECONDS);
        Assert.assertNotNull(message);
        assertEquals(Channel.META_SUBSCRIBE, message.getChannel());
        Assert.assertTrue(message.isSuccessful());

        String data = "data";
        aChannel.publish(data);
        message = messages.poll(5, TimeUnit.SECONDS);
        Assert.assertNotNull(message);
        assertEquals(data, message.getData());

        aChannel.unsubscribe(subscriber);
        message = metaMessages.poll(5, TimeUnit.SECONDS);
        Assert.assertNotNull(message);
        assertEquals(Channel.META_UNSUBSCRIBE, message.getChannel());
        Assert.assertTrue(message.isSuccessful());

        disconnectBayeuxClient(client);
    }
}
