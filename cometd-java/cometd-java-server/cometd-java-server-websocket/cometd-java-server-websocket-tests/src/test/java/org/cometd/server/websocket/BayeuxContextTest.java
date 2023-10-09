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
package org.cometd.server.websocket;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.websocket.jakarta.WebSocketTransport;
import org.cometd.server.websocket.jetty.JettyWebSocketTransport;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class BayeuxContextTest extends ClientServerWebSocketTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestHeaderIsCaseInsensitive(Transport wsType) throws Exception {
        prepareAndStart(wsType, null);

        CountDownLatch latch = new CountDownLatch(1);
        bayeuxServer.getChannel(Channel.META_HANDSHAKE).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                BayeuxContext context = message.getBayeuxContext();
                Assertions.assertEquals(context.getHeader("Host"), context.getHeader("HOST"));
                Assertions.assertEquals(context.getHeader("Host"), context.getHeaderValues("HOST").get(0));
                latch.countDown();
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient(wsType);
        client.handshake();

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCookiesSentToServer(Transport wsType) throws Exception {
        prepareAndStart(wsType, null);

        String cookieName = "name";
        String cookieValue = "value";
        CountDownLatch latch = new CountDownLatch(1);
        bayeuxServer.getChannel(Channel.META_HANDSHAKE).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                BayeuxContext context = message.getBayeuxContext();
                Assertions.assertEquals(cookieValue, context.getCookie(cookieName));
                latch.countDown();
                return true;
            }
        });

        BayeuxClient client = newBayeuxClient(wsType);
        client.putCookie(HttpCookie.from(cookieName, cookieValue));
        client.handshake();

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCookiesSentToClient(Transport wsType) throws Exception {
        String wsTransportClass = switch (wsType) {
            case WEBSOCKET_JAKARTA, WEBSOCKET_OKHTTP -> CookieWebSocketTransport.class.getName();
            case WEBSOCKET_JETTY -> CookieJettyWebSocketTransport.class.getName();
        };
        prepareServer(wsType, 0, "/cometd", null, wsTransportClass);
        startServer();
        prepareClient(wsType);
        startClient();

        BayeuxClient client = newBayeuxClient(wsType);
        client.handshake();

        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        HttpCookie cookie = client.getCookie(CookieConstants.COOKIE_NAME);
        Assertions.assertEquals(CookieConstants.COOKIE_VALUE, cookie.getValue());

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMultipleCookiesSentToServer(Transport wsType) throws Exception {
        prepareAndStart(wsType, null);

        List<String> cookieNames = List.of("a", "BAYEUX_BROWSER", "b");
        List<String> cookieValues = List.of("1", "761e1pplr7yo3wmsri1x5y0gnnby", "2");
        StringBuilder cookies = new StringBuilder();
        for (int i = 0; i < cookieNames.size(); ++i) {
            cookies.append(cookieNames.get(i)).append("=").append(cookieValues.get(i)).append("; ");
        }

        CountDownLatch latch = new CountDownLatch(1);
        bayeuxServer.getChannel(Channel.META_HANDSHAKE).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                BayeuxContext context = message.getBayeuxContext();
                for (int i = 0; i < cookieNames.size(); ++i) {
                    Assertions.assertEquals(cookieValues.get(i), context.getCookie(cookieNames.get(i)));
                }
                latch.countDown();
                return true;
            }
        });

        try (Socket socket = new Socket("localhost", connector.getLocalPort())) {
            OutputStream output = socket.getOutputStream();
            String upgrade = "" +
                             "GET " + cometdPath + " HTTP/1.1\r\n" +
                             "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                             "Connection: Upgrade\r\n" +
                             "Upgrade: websocket\r\n" +
                             "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                             "Sec-WebSocket-Version: 13\r\n" +
                             "Cookie: " + cookies + "\r\n" +
                             "\r\n";
            output.write(upgrade.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Wait for the upgrade to take place on server side.
            Thread.sleep(1000);

            String handshake = "" +
                               "{" +
                               "\"id\":\"1\"," +
                               "\"channel\":\"/meta/handshake\"," +
                               "\"version\":\"1.0\"," +
                               "\"supportedConnectionTypes\":[\"websocket\"]" +
                               "}";
            byte[] handshakeBytes = handshake.getBytes(StandardCharsets.UTF_8);
            Assertions.assertTrue(handshakeBytes.length <= 125); // Max payload length

            output.write(0x81); // FIN FLAG + TYPE=TEXT
            output.write(0x80 + handshakeBytes.length); // MASK FLAG + LENGTH
            output.write(new byte[]{0, 0, 0, 0}); // MASK BYTES
            output.write(handshakeBytes); // PAYLOAD
            output.flush();

            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSessionAttribute(Transport wsType) throws Exception {
        String wsTransportClass = switch (wsType) {
            case WEBSOCKET_JAKARTA, WEBSOCKET_OKHTTP -> SessionWebSocketTransport.class.getName();
            case WEBSOCKET_JETTY -> SessionJettyWebSocketTransport.class.getName();
        };
        prepareServer(wsType, 0, "/cometd", null, wsTransportClass);

        switch (wsType) {
            case WEBSOCKET_JAKARTA, WEBSOCKET_OKHTTP -> {
                context.insertHandler(new org.eclipse.jetty.ee10.servlet.SessionHandler());
                ((ServletContextHandler)context).addServlet(new ServletHolder(new HttpServlet() {
                    @Override
                    protected void service(HttpServletRequest request, HttpServletResponse response) {
                        HttpSession session = request.getSession(true);
                        session.setAttribute(SessionConstants.ATTRIBUTE_NAME, SessionConstants.ATTRIBUTE_VALUE);
                    }
                }), "/session");
            }
            case WEBSOCKET_JETTY -> {
                SessionHandler sessionHandler = new SessionHandler();
                context.insertHandler(sessionHandler);
                sessionHandler.insertHandler(new Handler.Wrapper() {
                    @Override
                    public boolean handle(Request request, Response response, Callback callback) throws Exception {
                        if ("/session".equals(Request.getPathInContext(request))) {
                            Session session = request.getSession(true);
                            session.setAttribute(SessionConstants.ATTRIBUTE_NAME, SessionConstants.ATTRIBUTE_VALUE);
                            callback.succeeded();
                            return true;
                        }
                        return super.handle(request, response, callback);
                    }
                });
            }
            default -> throw new IllegalArgumentException();
        }

        startServer();
        prepareClient(wsType);
        startClient();

        // Make an HTTP request to prime the HttpSession
        URI uri = URI.create("http://localhost:" + connector.getLocalPort() + "/session");
        ContentResponse response = httpClient.newRequest(uri)
                .path("/session")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assertions.assertEquals(200, response.getStatus());

        List<HttpCookie> cookies = httpClient.getHttpCookieStore().match(uri);
        Assertions.assertNotNull(cookies);
        HttpCookie sessionCookie = null;
        for (HttpCookie cookie : cookies) {
            if ("jsessionid".equalsIgnoreCase(cookie.getName())) {
                sessionCookie = cookie;
            }
        }
        Assertions.assertNotNull(sessionCookie);

        CountDownLatch latch = new CountDownLatch(1);
        bayeuxServer.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                Assertions.assertEquals(SessionConstants.ATTRIBUTE_VALUE, message.getBayeuxContext().getSessionAttribute(SessionConstants.ATTRIBUTE_NAME));
                latch.countDown();
            }
        });

        BayeuxClient client = newBayeuxClient(wsType);
        client.getHttpCookieStore().add(uri, sessionCookie);
        client.handshake();

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testContextAttribute(Transport wsType) throws Exception {
        prepareAndStart(wsType, null);

        CountDownLatch latch = new CountDownLatch(1);
        bayeuxServer.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                Assertions.assertSame(bayeuxServer, message.getBayeuxContext().getContextAttribute(BayeuxServer.ATTRIBUTE));
                latch.countDown();
            }
        });

        BayeuxClient client = newBayeuxClient(wsType);
        client.handshake();

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        disconnectBayeuxClient(client);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testConcurrentClientsHaveDifferentBayeuxContexts(Transport wsType) throws Exception {
        String wsTransportClass = switch (wsType) {
            case WEBSOCKET_JAKARTA, WEBSOCKET_OKHTTP -> ConcurrentBayeuxContextWebSocketTransport.class.getName();
            case WEBSOCKET_JETTY -> ConcurrentBayeuxContextJettyWebSocketTransport.class.getName();
        };
        prepareServer(wsType, 0, "/cometd", null, wsTransportClass);
        startServer();
        prepareClient(wsType);
        startClient();

        // The first client will be held by the server.
        BayeuxClient client1 = newBayeuxClient(wsType);
        // The connect operation is blocking, so we must use another thread.
        new Thread(client1::handshake).start();

        // Wait for the first client to arrive at the concurrency point.
        switch (wsType) {
            case WEBSOCKET_JAKARTA, WEBSOCKET_OKHTTP -> {
                CountDownLatch enterLatch = ((ConcurrentBayeuxContextWebSocketTransport)bayeuxServer.getTransport("websocket")).enterLatch;
                Assertions.assertTrue(enterLatch.await(5, TimeUnit.SECONDS));
            }
            case WEBSOCKET_JETTY -> {
                CountDownLatch enterLatch = ((ConcurrentBayeuxContextJettyWebSocketTransport)bayeuxServer.getTransport("websocket")).enterLatch;
                Assertions.assertTrue(enterLatch.await(5, TimeUnit.SECONDS));
            }
            default -> throw new IllegalArgumentException();
        }

        // Connect the second client.
        BayeuxClient client2 = newBayeuxClient(wsType);
        client2.handshake();
        Assertions.assertTrue(client2.waitFor(1000, BayeuxClient.State.CONNECTED));

        // Release the first client.
        switch (wsType) {
            case WEBSOCKET_JAKARTA, WEBSOCKET_OKHTTP ->
                    ((ConcurrentBayeuxContextWebSocketTransport)bayeuxServer.getTransport("websocket")).proceedLatch.countDown();
            case WEBSOCKET_JETTY ->
                    ((ConcurrentBayeuxContextJettyWebSocketTransport)bayeuxServer.getTransport("websocket")).proceedLatch.countDown();
            default -> throw new IllegalArgumentException();
        }
        Assertions.assertTrue(client1.waitFor(1000, BayeuxClient.State.CONNECTED));

        String channelName = "/service/test";
        Map<String, BayeuxContext> contexts = new ConcurrentHashMap<>();
        CountDownLatch contextLatch = new CountDownLatch(2);
        bayeuxServer.createChannelIfAbsent(channelName).getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                contexts.put(from.getId(), message.getBayeuxContext());
                contextLatch.countDown();
                return true;
            }
        });

        client1.getChannel(channelName).publish("data");
        client2.getChannel(channelName).publish("data");
        Assertions.assertTrue(contextLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertEquals(2, contexts.size());
        Assertions.assertNotSame(contexts.get(client1.getId()), contexts.get(client2.getId()));

        disconnectBayeuxClient(client1);
        disconnectBayeuxClient(client2);
    }

    public interface CookieConstants {
        public static final String COOKIE_NAME = "name";
        public static final String COOKIE_VALUE = "value";
    }

    public static class CookieWebSocketTransport extends WebSocketTransport implements CookieConstants {
        public CookieWebSocketTransport(BayeuxServerImpl bayeux) {
            super(bayeux);
        }

        @Override
        protected void modifyHandshake(HandshakeRequest request, HandshakeResponse response) {
            response.getHeaders().put("Set-Cookie", List.of(COOKIE_NAME + "=" + COOKIE_VALUE));
        }
    }

    public static class CookieJettyWebSocketTransport extends JettyWebSocketTransport implements CookieConstants {
        public CookieJettyWebSocketTransport(BayeuxServerImpl bayeux) {
            super(bayeux);
        }

        @Override
        protected void modifyUpgrade(ServerUpgradeRequest request, ServerUpgradeResponse response) {
            response.getHeaders().put("Set-Cookie", COOKIE_NAME + "=" + COOKIE_VALUE);
        }
    }

    public interface SessionConstants {
        public static final String ATTRIBUTE_NAME = "name";
        public static final String ATTRIBUTE_VALUE = "value";
    }

    public static class SessionWebSocketTransport extends WebSocketTransport implements SessionConstants {
        public SessionWebSocketTransport(BayeuxServerImpl bayeux) {
            super(bayeux);
        }

        @Override
        protected void modifyHandshake(HandshakeRequest request, HandshakeResponse response) {
            HttpSession session = (HttpSession)request.getHttpSession();
            Assertions.assertNotNull(session);
            Assertions.assertEquals(ATTRIBUTE_VALUE, session.getAttribute(ATTRIBUTE_NAME));
        }
    }

    public static class SessionJettyWebSocketTransport extends JettyWebSocketTransport implements SessionConstants {
        public SessionJettyWebSocketTransport(BayeuxServerImpl bayeux) {
            super(bayeux);
        }

        @Override
        protected void modifyUpgrade(ServerUpgradeRequest request, ServerUpgradeResponse response) {
            Session session = request.getSession(false);
            Assertions.assertNotNull(session);
            Assertions.assertEquals(ATTRIBUTE_VALUE, session.getAttribute(ATTRIBUTE_NAME));
        }
    }

    public static class ConcurrentBayeuxContextWebSocketTransport extends WebSocketTransport {
        private final AtomicInteger handshakes = new AtomicInteger();
        private final CountDownLatch enterLatch = new CountDownLatch(1);
        private final CountDownLatch proceedLatch = new CountDownLatch(1);

        public ConcurrentBayeuxContextWebSocketTransport(BayeuxServerImpl bayeux) {
            super(bayeux);
        }

        @Override
        protected void modifyHandshake(HandshakeRequest request, HandshakeResponse response) {
            onUpgrade(handshakes, enterLatch, proceedLatch);
            super.modifyHandshake(request, response);
        }
    }

    public static class ConcurrentBayeuxContextJettyWebSocketTransport extends JettyWebSocketTransport {
        private final AtomicInteger handshakes = new AtomicInteger();
        private final CountDownLatch enterLatch = new CountDownLatch(1);
        private final CountDownLatch proceedLatch = new CountDownLatch(1);

        public ConcurrentBayeuxContextJettyWebSocketTransport(BayeuxServerImpl bayeux) {
            super(bayeux);
        }

        @Override
        protected void modifyUpgrade(ServerUpgradeRequest request, ServerUpgradeResponse response) {
            onUpgrade(handshakes, enterLatch, proceedLatch);
            super.modifyUpgrade(request, response);
        }
    }

    private static void onUpgrade(AtomicInteger handshakes, CountDownLatch enterLatch, CountDownLatch proceedLatch) {
        int count = handshakes.incrementAndGet();
        if (count == 1) {
            try {
                enterLatch.countDown();
                if (!proceedLatch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException();
                }
            } catch (InterruptedException x) {
                throw new IllegalStateException(x);
            }
        }
    }
}
