/*
 * Copyright (c) 2008-2019 the original author or authors.
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
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.transport.JSONTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class ConcurrentConnectDisconnectTest extends AbstractBayeuxClientServerTest {
    public ConcurrentConnectDisconnectTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception {
        startServer(null);
    }

    @Test
    public void testConnectListenerThenDisconnectThenConnectHandler() throws Exception {
        final CountDownLatch connectLatch = new CountDownLatch(2);
        final CountDownLatch disconnectLatch = new CountDownLatch(1);
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
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        String channelName = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"clientId\": \"" + clientId + "\"," +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"" + channelName + "\"" +
                "}]");
        response = subscribe.send();
        Assert.assertEquals(200, response.getStatus());

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());

        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        FutureResponseListener futureResponse = new FutureResponseListener(connect2);
        connect2.send(futureResponse);

        // Wait for the second connect to arrive, then disconnect
        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assert.assertEquals(200, response.getStatus());

        disconnectLatch.countDown();

        response = futureResponse.get(timeout * 2, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        JettyJSONContextClient parser = new JettyJSONContextClient();
        Message.Mutable connectReply2 = parser.parse(response.getContentAsString())[0];
        Assert.assertFalse(connectReply2.isSuccessful());
        String error = (String)connectReply2.get(Message.ERROR_FIELD);
        Assert.assertNotNull(error);
        Map<String, Object> advice = connectReply2.getAdvice();
        Assert.assertNotNull(advice);
        Assert.assertEquals(Message.RECONNECT_NONE_VALUE, advice.get(Message.RECONNECT_FIELD));

        Assert.assertNull(bayeux.getSession(clientId));

        // Test that sending a connect for an expired session
        // will return an advice with reconnect:"handshake"
        Request connect3 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect3.send();
        Assert.assertEquals(200, response.getStatus());
        Message.Mutable connectReply3 = parser.parse(response.getContentAsString())[0];
        advice = connectReply3.getAdvice();
        Assert.assertNotNull(advice);
        Assert.assertEquals(Message.RECONNECT_HANDSHAKE_VALUE, advice.get(Message.RECONNECT_FIELD));
    }

    @Test
    @Ignore("fix this test using session suspend listener")
    public void testConnectHandlerThenDisconnect() throws Exception {
        final CountDownLatch connectLatch = new CountDownLatch(2);
        final CountDownLatch disconnectLatch = new CountDownLatch(1);
        final CountDownLatch suspendLatch = new CountDownLatch(1);
        JSONTransport transport = new JSONTransport(bayeux) {
            @Override
            protected void handleMessage(Context context, ServerMessage.Mutable message, Promise<ServerMessage.Mutable> promise) {
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

//            @Override
//            protected void metaConnectSuspended(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSession session) {
//                suspendLatch.countDown();
//            }
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
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        String channelName = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"clientId\": \"" + clientId + "\"," +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"" + channelName + "\"" +
                "}]");
        response = subscribe.send();
        Assert.assertEquals(200, response.getStatus());

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());

        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        FutureResponseListener futureResponse = new FutureResponseListener(connect2);
        connect2.send(futureResponse);

        // Wait for the second connect to arrive, then disconnect
        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assert.assertEquals(200, response.getStatus());

        disconnectLatch.countDown();

        // The connect must not be suspended
        Assert.assertFalse(suspendLatch.await(1, TimeUnit.SECONDS));

        response = futureResponse.get(timeout * 2, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        Assert.assertTrue(response.getContentAsString().toLowerCase().contains("\"none\""));

        Assert.assertNull(bayeux.getSession(clientId));
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
        }
    }
}
