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
package org.cometd.server.http;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.http.jetty.transport.HandlerJSONTransport;
import org.cometd.server.transport.AbstractHttpTransport;
import org.cometd.server.transport.TransportContext;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FutureResponseListener;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ConcurrentConnectDisconnectTest extends AbstractBayeuxClientServerTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testConnectListenerThenDisconnectThenConnectHandler(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        bayeux.getChannel("/meta/connect").addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                connectLatch.countDown();
                if (connectLatch.getCount() == 0) {
                    await(disconnectLatch);
                }
                return true;
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        String channelName = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"clientId\": \"" + clientId + "\"," +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"" + channelName + "\"" +
                "}]");
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());

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

        // Wait for the second connect to arrive, then disconnect
        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assertions.assertEquals(200, response.getStatus());

        disconnectLatch.countDown();

        response = futureResponse.get(timeout * 2, TimeUnit.SECONDS);
        Assertions.assertEquals(200, response.getStatus());

        JettyJSONContextClient parser = new JettyJSONContextClient();
        Message.Mutable connectReply2 = parser.parse(response.getContentAsString())[0];
        Assertions.assertFalse(connectReply2.isSuccessful());
        String error = (String)connectReply2.get(Message.ERROR_FIELD);
        Assertions.assertNotNull(error);
        Map<String, Object> advice = connectReply2.getAdvice();
        Assertions.assertNotNull(advice);
        Assertions.assertEquals(Message.RECONNECT_NONE_VALUE, advice.get(Message.RECONNECT_FIELD));

        Assertions.assertNull(bayeux.getSession(clientId));

        // Test that sending a connect for an expired session
        // will return an advice with reconnect:"handshake"
        Request connect3 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect3.send();
        Assertions.assertEquals(200, response.getStatus());
        Message.Mutable connectReply3 = parser.parse(response.getContentAsString())[0];
        advice = connectReply3.getAdvice();
        Assertions.assertNotNull(advice);
        Assertions.assertEquals(Message.RECONNECT_HANDSHAKE_VALUE, advice.get(Message.RECONNECT_FIELD));
    }

    @ParameterizedTest
    @MethodSource("transports")
    @Disabled("fix this test using session suspend listener")
    public void testConnectHandlerThenDisconnect(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        CountDownLatch connectLatch = new CountDownLatch(2);
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        CountDownLatch suspendLatch = new CountDownLatch(1);
        HandlerJSONTransport transport = new HandlerJSONTransport(bayeux) {
            @Override
            protected void handleMessage(TransportContext context, ServerMessage.Mutable message, Promise<ServerMessage.Mutable> promise) {
                super.handleMessage(context, message, Promise.from(reply -> {
                    promise.succeed(reply);
                    if (Channel.META_CONNECT.equals(message.getChannel())) {
                        connectLatch.countDown();
                        if (connectLatch.getCount() == 0) {
                            await(disconnectLatch);
                        }
                    }
                }, promise::fail));
            }

            @Override
            protected AbstractHttpTransport.HttpScheduler suspend(TransportContext context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
                suspendLatch.countDown();
                return super.suspend(context, promise, message, timeout);
            }
        };
        transport.init();
        bayeux.setTransports(transport);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        String channelName = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"clientId\": \"" + clientId + "\"," +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"" + channelName + "\"" +
                "}]");
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());

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

        // Wait for the second connect to arrive, then disconnect
        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assertions.assertEquals(200, response.getStatus());

        disconnectLatch.countDown();

        // The connect must not be suspended
        Assertions.assertFalse(suspendLatch.await(1, TimeUnit.SECONDS));

        response = futureResponse.get(timeout * 2, TimeUnit.SECONDS);
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertTrue(response.getContentAsString().toLowerCase().contains("\"none\""));

        Assertions.assertNull(bayeux.getSession(clientId));
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
        }
    }
}
