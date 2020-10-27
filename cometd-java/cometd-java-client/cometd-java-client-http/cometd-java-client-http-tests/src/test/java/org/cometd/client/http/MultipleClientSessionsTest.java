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

import java.net.HttpCookie;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.http.AbstractHttpTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Simulates a browser opening multiple tabs to the same Bayeux server
 */
public class MultipleClientSessionsTest extends ClientServerTest {
    private final long timeout = 7000L;

    private Map<String, String> serverOptions() {
        Map<String, String> options = new HashMap<>();
        options.put("timeout", String.valueOf(timeout));
        return options;
    }

    @Test
    public void testMultipleClientSession_WithOneMaxSessionPerBrowser_WithNoMultiSessionInterval() throws Exception {
        Map<String, String> options = serverOptions();
        options.put(AbstractHttpTransport.MAX_SESSIONS_PER_BROWSER_OPTION, "1");
        options.put(AbstractHttpTransport.MULTI_SESSION_INTERVAL_OPTION, "0");
        start(options);

        BayeuxClient client1 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects1 = new ConcurrentLinkedQueue<>();
        CountDownLatch latch1 = new CountDownLatch(2);
        client1.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            connects1.offer(message);
            latch1.countDown();
        });
        client1.handshake();

        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        HttpCookie browserCookie = client1.getCookie("BAYEUX_BROWSER");
        Assertions.assertNotNull(browserCookie);

        // Give some time to the first client to establish the long poll before the second client
        Thread.sleep(1000);

        BayeuxClient client2 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects2 = new ConcurrentLinkedQueue<>();
        CountDownLatch latch2 = new CountDownLatch(1);
        client2.putCookie(browserCookie);
        client2.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            connects2.offer(message);
            latch2.countDown();
        });
        client2.handshake();

        Assertions.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(1, connects2.size());
        Message connect2 = connects2.peek();
        Assertions.assertNotNull(connect2);
        Map<String, Object> advice2 = connect2.getAdvice();
        Assertions.assertEquals(Message.RECONNECT_NONE_VALUE, advice2.get(Message.RECONNECT_FIELD));
        Assertions.assertSame(Boolean.TRUE, advice2.get("multiple-clients"));
        Assertions.assertFalse(connect2.isSuccessful());

        // Give some time to the second client to process the disconnect
        Thread.sleep(1000);
        Assertions.assertFalse(client2.isConnected());

        Assertions.assertTrue(latch1.await(timeout, TimeUnit.MILLISECONDS));
        Assertions.assertEquals(2, connects1.size());
        Assertions.assertTrue(client1.isConnected());

        disconnectBayeuxClient(client1);
    }

    @Test
    public void testMultipleClientSession_WithOneMaxSessionPerBrowser_WithMultiSessionInterval() throws Exception {
        long multiSessionInterval = 1500;
        Map<String, String> options = serverOptions();
        options.put(AbstractHttpTransport.MAX_SESSIONS_PER_BROWSER_OPTION, "1");
        options.put(AbstractHttpTransport.MULTI_SESSION_INTERVAL_OPTION, String.valueOf(multiSessionInterval));
        start(options);

        BayeuxClient client1 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects1 = new ConcurrentLinkedQueue<>();
        client1.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                connects1.offer(message);
            }
        });
        client1.handshake();
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        HttpCookie browserCookie = client1.getCookie("BAYEUX_BROWSER");
        Assertions.assertNotNull(browserCookie);

        // Give some time to the first client to establish the long poll before the second client
        Thread.sleep(1000);

        BayeuxClient client2 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects2 = new ConcurrentLinkedQueue<>();
        client2.putCookie(browserCookie);
        client2.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connects2.offer(message));
        client2.handshake();
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        Thread.sleep(1000);

        BayeuxClient client3 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects3 = new ConcurrentLinkedQueue<>();
        client3.putCookie(browserCookie);
        client3.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connects3.offer(message));
        client3.handshake();
        Assertions.assertTrue(client3.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Sleep for a while
        Thread.sleep(2 * multiSessionInterval);

        // The first client must remain in long poll mode
        Assertions.assertEquals(1, connects1.size());

        // Second client must be in normal poll mode
        Assertions.assertTrue(connects2.size() > 1);
        Message lastConnect2 = new LinkedList<>(connects2).getLast();
        Map<String, Object> advice2 = lastConnect2.getAdvice();
        Assertions.assertNotNull(advice2);
        Assertions.assertSame(Boolean.TRUE, advice2.get("multiple-clients"));

        // Third client must be in normal poll mode
        Assertions.assertTrue(connects3.size() > 1);
        Message lastConnect3 = new LinkedList<>(connects3).getLast();
        Map<String, Object> advice3 = lastConnect3.getAdvice();
        Assertions.assertNotNull(advice3);
        Assertions.assertSame(Boolean.TRUE, advice3.get("multiple-clients"));

        // Wait for the first client to re-issue a long poll
        Thread.sleep(timeout);

        // First client must still be in long poll mode
        Assertions.assertEquals(2, connects1.size());

        // Abort abruptly the first client
        // Another client must switch to long poll
        client1.abort();

        // Sleep another timeout to be sure client1 does not poll
        Thread.sleep(timeout);
        Assertions.assertEquals(2, connects1.size());

        // Loop until one of the other clients switched to long poll
        BayeuxClient client4 = null;
        BayeuxClient client5 = null;
        for (int i = 0; i < 10; ++i) {
            lastConnect2 = new LinkedList<>(connects2).getLast();
            advice2 = lastConnect2.getAdvice();
            if (advice2 == null || !advice2.containsKey("multiple-clients")) {
                client4 = client2;
                client5 = client3;
                break;
            }
            lastConnect3 = new LinkedList<>(connects3).getLast();
            advice3 = lastConnect3.getAdvice();
            if (advice3 == null || !advice3.containsKey("multiple-clients")) {
                client4 = client3;
                client5 = client2;
                break;
            }
            Thread.sleep(timeout / 10);
        }
        Assertions.assertNotNull(client4);

        // Disconnect this client normally, the last client must switch to long poll
        disconnectBayeuxClient(client4);

        // Be sure the last client had the time to switch to long poll mode
        Thread.sleep(timeout + 2 * multiSessionInterval);
        Message lastConnect;
        if (client5 == client2) {
            lastConnect = new LinkedList<>(connects2).getLast();
        } else {
            lastConnect = new LinkedList<>(connects3).getLast();
        }
        Map<String, Object> advice = lastConnect.getAdvice();
        Assertions.assertTrue(advice == null || !advice.containsKey("multiple-clients"));

        disconnectBayeuxClient(client5);
    }

    @Test
    public void testMultipleClientSession_WithTwoMaxSessionPerBrowser_WithMultiSessionInterval() throws Exception {
        long multiSessionInterval = 1500;
        Map<String, String> options = serverOptions();
        options.put(AbstractHttpTransport.MAX_SESSIONS_PER_BROWSER_OPTION, "2");
        options.put(AbstractHttpTransport.MULTI_SESSION_INTERVAL_OPTION, String.valueOf(multiSessionInterval));
        start(options);

        BayeuxClient client1 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects1 = new ConcurrentLinkedQueue<>();
        client1.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                connects1.offer(message);
            }
        });
        client1.handshake();
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        HttpCookie browserCookie = client1.getCookie("BAYEUX_BROWSER");
        Assertions.assertNotNull(browserCookie);

        // Give some time to the first client to establish the long poll before the second client
        Thread.sleep(1000);

        BayeuxClient client2 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects2 = new ConcurrentLinkedQueue<>();
        client2.putCookie(browserCookie);
        client2.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connects2.offer(message));
        client2.handshake();
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        Thread.sleep(1000);

        BayeuxClient client3 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects3 = new ConcurrentLinkedQueue<>();
        client3.putCookie(browserCookie);
        client3.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connects3.offer(message));
        client3.handshake();
        Assertions.assertTrue(client3.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Sleep for a while
        Thread.sleep(2 * multiSessionInterval);

        // The first client must remain in long poll mode
        Assertions.assertEquals(1, connects1.size());

        // Second client must remain in long poll mode
        Assertions.assertEquals(1, connects2.size());

        // Third client must be in normal poll mode
        Assertions.assertTrue(connects3.size() > 1);
        Message lastConnect3 = new LinkedList<>(connects3).getLast();
        Map<String, Object> advice3 = lastConnect3.getAdvice();
        Assertions.assertNotNull(advice3);
        Assertions.assertSame(Boolean.TRUE, advice3.get("multiple-clients"));

        // Wait for the first and second clients to re-issue a long poll
        Thread.sleep(timeout);

        // First and second clients must still be in long poll mode
        Assertions.assertEquals(2, connects1.size());
        Assertions.assertEquals(2, connects2.size());

        // Abort abruptly the first client
        // Third client must switch to long poll
        client1.abort();

        // Sleep another timeout to be sure client1 does not poll
        Thread.sleep(timeout);
        Assertions.assertEquals(2, connects1.size());

        // Loop until client3 switched to long poll
        for (int i = 0; i < 10; ++i) {
            lastConnect3 = new LinkedList<>(connects3).getLast();
            advice3 = lastConnect3.getAdvice();
            if (advice3 == null || !advice3.containsKey("multiple-clients")) {
                break;
            }
            Thread.sleep(timeout / 10);
        }

        lastConnect3 = new LinkedList<>(connects3).getLast();
        advice3 = lastConnect3.getAdvice();
        Assertions.assertTrue(advice3 == null || !advice3.containsKey("multiple-clients"));

        disconnectBayeuxClient(client2);

        disconnectBayeuxClient(client3);
    }

    @Test
    public void testMultipleClientSession_WhenSameClientSendsTwoConnects() throws Exception {
        long multiSessionInterval = 1500;
        int duplicateMetaConnectHttpCode = HttpStatus.BAD_REQUEST_400;
        Map<String, String> options = serverOptions();
        options.put(AbstractHttpTransport.MAX_SESSIONS_PER_BROWSER_OPTION, "1");
        options.put(AbstractHttpTransport.MULTI_SESSION_INTERVAL_OPTION, String.valueOf(multiSessionInterval));
        options.put(AbstractHttpTransport.DUPLICATE_META_CONNECT_HTTP_RESPONSE_CODE_OPTION, String.valueOf(duplicateMetaConnectHttpCode));
        start(options);

        JSONContext.Client parser = new JettyJSONContextClient();

        String handshakeContent = "[{" +
                "\"id\":\"1\"," +
                "\"channel\":\"/meta/handshake\"," +
                "\"version\":\"1.0\"," +
                "\"supportedConnectionTypes\":[\"long-polling\"]" +
                "}]";
        ContentResponse handshake = httpClient.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.POST)
                .path(cometdServletPath)
                .body(new StringRequestContent("application/json;charset=UTF-8", handshakeContent))
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assertions.assertEquals(200, handshake.getStatus());
        List<HttpCookie> cookies = httpClient.getCookieStore().get(URI.create(cometdURL));
        Assertions.assertEquals(1, cookies.size());
        HttpCookie browserCookie = cookies.get(0);
        Assertions.assertEquals("BAYEUX_BROWSER", browserCookie.getName());
        Message.Mutable[] messages = parser.parse(handshake.getContentAsString());
        Assertions.assertEquals(1, messages.length);
        String clientId = messages[0].getClientId();

        String connectContent1 = "[{" +
                "\"id\":\"2\"," +
                "\"channel\":\"/meta/connect\"," +
                "\"connectionType\":\"long-polling\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"advice\": {\"timeout\":0}" +
                "}]";
        ContentResponse connect1 = httpClient.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.POST)
                .path(cometdServletPath)
                .body(new StringRequestContent("application/json;charset=UTF-8", connectContent1))
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assertions.assertEquals(200, connect1.getStatus());

        // This /meta/connect is suspended.
        CountDownLatch abortedConnectLatch = new CountDownLatch(1);
        String connectContent2 = "[{" +
                "\"id\":\"3\"," +
                "\"channel\":\"/meta/connect\"," +
                "\"connectionType\":\"long-polling\"," +
                "\"clientId\":\"" + clientId + "\"" +
                "}]";
        httpClient.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.POST)
                .path(cometdServletPath)
                .body(new StringRequestContent("application/json;charset=UTF-8", connectContent2))
                .timeout(5, TimeUnit.SECONDS)
                .send(result -> {
                    Assertions.assertTrue(result.isSucceeded());
                    Assertions.assertEquals(duplicateMetaConnectHttpCode, result.getResponse().getStatus());
                    abortedConnectLatch.countDown();
                });

        // Give some time to the long poll to happen.
        Thread.sleep(1000);

        // Send the second /meta/connect before the previous returns.
        String connectContent3 = "[{" +
                "\"id\":\"4\"," +
                "\"channel\":\"/meta/connect\"," +
                "\"connectionType\":\"long-polling\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"advice\": {\"timeout\":0}" +
                "}]";
        ContentResponse connect3 = httpClient.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.POST)
                .path(cometdServletPath)
                .body(new StringRequestContent("application/json;charset=UTF-8", connectContent3))
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assertions.assertEquals(200, connect3.getStatus());

        Assertions.assertTrue(abortedConnectLatch.await(5, TimeUnit.SECONDS));

        // Make sure a subsequent connect does not have the multiple-clients advice.
        String connectContent4 = "[{" +
                "\"id\":\"5\"," +
                "\"channel\":\"/meta/connect\"," +
                "\"connectionType\":\"long-polling\"," +
                "\"clientId\":\"" + clientId + "\"" +
                "}]";
        ContentResponse connect4 = httpClient.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.POST)
                .path(cometdServletPath)
                .body(new StringRequestContent("application/json;charset=UTF-8", connectContent4))
                .timeout(2 * timeout, TimeUnit.MILLISECONDS)
                .send();
        Assertions.assertEquals(200, connect4.getStatus());
        messages = parser.parse(connect4.getContentAsString());
        Assertions.assertEquals(1, messages.length);
        Message.Mutable message = messages[0];
        Map<String, Object> advice = message.getAdvice(true);
        Assertions.assertFalse(advice.containsKey("multiple-clients"));
    }

    @Test
    public void testMultipleClientSession_WithNoMaxSessionPerBrowser() throws Exception {
        Map<String, String> options = serverOptions();
        options.put(AbstractHttpTransport.MAX_SESSIONS_PER_BROWSER_OPTION, "-1");
        start(options);

        BayeuxClient client1 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects1 = new ConcurrentLinkedQueue<>();
        client1.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                connects1.offer(message);
            }
        });
        client1.handshake();
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        HttpCookie browserCookie = client1.getCookie("BAYEUX_BROWSER");
        Assertions.assertNotNull(browserCookie);

        // Wait for /meta/connect to be held.
        Thread.sleep(1000);

        BayeuxClient client2 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects2 = new ConcurrentLinkedQueue<>();
        client2.putCookie(browserCookie);
        client2.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connects2.offer(message));
        client2.handshake();
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for /meta/connect to be held.
        Thread.sleep(1000);

        // At this point, both clients should have their /meta/connect held.
        Assertions.assertEquals(1, connects1.size());
        Assertions.assertEquals(1, connects2.size());

        disconnectBayeuxClient(client1);
        disconnectBayeuxClient(client2);
    }
}
