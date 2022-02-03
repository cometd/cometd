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
package org.cometd.server;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CustomAdviceTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testCustomTimeoutViaMessage(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        CountDownLatch connectLatch = new CountDownLatch(2);
        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                connectLatch.countDown();
                return true;
            }
        });

        String channelName = "/connect";
        long newTimeout = timeout / 2;
        bayeux.createChannelIfAbsent(channelName, channel -> channel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                from.setTimeout(newTimeout);
                return true;
            }
        }));

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());

        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        FutureResponseListener futureResponse = new FutureResponseListener(connect2);
        connect2.send(futureResponse);
        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        Request publish = newBayeuxRequest("[{" +
                "\"channel\":\"" + channelName + "\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        // Wait for the second connect to return
        response = futureResponse.get(timeout * 2, TimeUnit.SECONDS);
        Assertions.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Message.Mutable connect = messages[0];
        Map<String, Object> advice = connect.getAdvice();
        Assertions.assertNotNull(advice);
        Number timeout = (Number)advice.get("timeout");
        Assertions.assertNotNull(timeout);
        Assertions.assertEquals(newTimeout, timeout.longValue());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCustomIntervalViaMessage(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        CountDownLatch connectLatch = new CountDownLatch(2);
        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                connectLatch.countDown();
                return true;
            }
        });

        String channelName = "/interval";
        long newInterval = 1000;
        bayeux.createChannelIfAbsent(channelName, channel -> channel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                from.setInterval(newInterval);
                return true;
            }
        }));

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());

        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        FutureResponseListener futureResponse = new FutureResponseListener(connect2);
        connect2.send(futureResponse);
        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        Request publish = newBayeuxRequest("[{" +
                "\"channel\":\"" + channelName + "\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        // Wait for the second connect to return
        response = futureResponse.get(timeout * 2, TimeUnit.SECONDS);
        Assertions.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Message.Mutable connect = messages[0];
        Map<String, Object> advice = connect.getAdvice();
        Assertions.assertNotNull(advice);
        Number interval = (Number)advice.get("interval");
        Assertions.assertNotNull(interval);
        Assertions.assertEquals(newInterval, interval.longValue());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCustomIntervalViaAdvice(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());

        // The client tells the server that it's going to sleep and won't connect for a while
        // The server must adjust to not expire its session
        long newInterval = 1000;
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"," +
                "\"advice\":{" +
                "    \"timeout\": 0," +
                "    \"interval\": " + newInterval +
                "}" +
                "}]");
        response = connect2.send();
        Assertions.assertEquals(200, response.getStatus());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.getContentAsString());
        Message.Mutable connect = messages[0];
        Map<String, Object> advice = connect.getAdvice();
        Assertions.assertNotNull(advice);
        Number interval = (Number)advice.get("interval");
        Assertions.assertNotNull(interval);
        Assertions.assertEquals(newInterval, interval.longValue());

        // Verify that the server is aware of the interval and will not expire the session
        ServerSessionImpl session = (ServerSessionImpl)bayeux.getSession(clientId);
        long expectedExpire = TimeUnit.NANOSECONDS.toMillis(session.getIntervalTimestamp() - System.nanoTime());
        Assertions.assertTrue(expectedExpire > session.getServerTransport().getMaxInterval() + newInterval / 2);
    }
}
