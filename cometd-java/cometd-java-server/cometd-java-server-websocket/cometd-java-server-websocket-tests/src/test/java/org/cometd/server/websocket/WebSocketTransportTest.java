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
package org.cometd.server.websocket;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDServlet;
import org.cometd.server.websocket.javax.WebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WebSocketTransportTest {
    private Server server;
    private ServerConnector connector;
    private HttpClient httpClient;
    private WebSocketClient client;
    private BayeuxServerImpl bayeux;

    @AfterEach
    public void dispose() throws Exception {
        if (client != null) {
            client.stop();
        }
        if (httpClient != null) {
            httpClient.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    private void startServer(Map<String, String> params) throws Exception {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");
        WebSocketServerContainerInitializer.configure(context, null);

        String cometdURLMapping = "/cometd/*";
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter("transports", WebSocketTransport.class.getName());
        cometdServletHolder.setInitParameter("timeout", "10000");
        cometdServletHolder.setInitParameter("ws.cometdURLMapping", cometdURLMapping);
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
            }
        }
        cometdServletHolder.setInitOrder(1);
        context.addServlet(cometdServletHolder, cometdURLMapping);

        server.start();
        bayeux = (BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
    }

    private void startClient() throws Exception {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        httpClient = new HttpClient();
        httpClient.setExecutor(clientThreads);
        httpClient.start();
        client = new WebSocketClient(httpClient);
        client.start();
    }

    @Test
    public void testClientAbortsAfterFirstMetaConnect() throws Exception {
        long maxInterval = 2000;

        Map<String, String> params = new HashMap<>();
        params.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        startServer(params);
        startClient();

        BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/cometd");
        Session wsSession = client.connect(new WebSocketEndPoint(messages), uri).get(5, TimeUnit.SECONDS);

        String handshake = "[{" +
                "\"id\":\"1\"," +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"websocket\"]" +
                "}]";
        wsSession.getRemote().sendString(handshake);

        Message message = messages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        Assertions.assertTrue(message.isSuccessful());
        String clientId = message.getClientId();

        String connect = "[{" +
                "\"id\":\"2\"," +
                "\"channel\":\"/meta/connect\"," +
                "\"connectionType\":\"websocket\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"advice\": {\"timeout\":0}" +
                "}]";
        wsSession.getRemote().sendString(connect);

        ServerSession session = bayeux.getSession(clientId);

        CountDownLatch removeLatch = new CountDownLatch(1);
        session.addListener((ServerSession.RemovedListener)(s, m, t) -> removeLatch.countDown());
        // Disconnect the client abruptly.
        disconnect(wsSession);
        // Add messages for the client; the first message is written to
        // buffers, but the second message throws while trying to write it.
        session.deliver(null, "/foo", "bar1", Promise.noop());
        session.deliver(null, "/foo", "bar2", Promise.noop());

        // The session should expire on the server.
        Assertions.assertTrue(removeLatch.await(3 * maxInterval, TimeUnit.MILLISECONDS));
        Assertions.assertNull(bayeux.getSession(clientId));
    }

    @Test
    public void testMetaConnectConcurrentWithExpiration() throws Exception {
        long maxInterval = 2000;
        Map<String, String> params = new HashMap<>();
        params.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        startServer(params);
        startClient();

        BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/cometd");
        Session session = client.connect(new WebSocketEndPoint(messages), uri).get(5, TimeUnit.SECONDS);

        String handshake = "[{" +
                "\"id\":\"1\"," +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"websocket\"]" +
                "}]";
        session.getRemote().sendString(handshake);

        Message message = messages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        Assertions.assertTrue(message.isSuccessful());

        // Expire the session when receiving the first /meta/connect.
        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession sender, ServerChannel channel, ServerMessage.Mutable message) {
                // Simulate that the session expired exactly when this message was received.
                bayeux.removeServerSession(sender, true);
                return true;
            }
        });

        String clientId = message.getClientId();
        String connect = "[{" +
                "\"id\":\"2\"," +
                "\"channel\":\"/meta/connect\"," +
                "\"connectionType\":\"websocket\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"advice\": {\"timeout\":0}" +
                "}]";
        session.getRemote().sendString(connect);

        message = messages.poll(2 * maxInterval, TimeUnit.MILLISECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(Channel.META_CONNECT, message.getChannel());
        Assertions.assertFalse(message.isSuccessful());
        Map<String, Object> advice = message.getAdvice();
        Assertions.assertNotNull(advice);
        Assertions.assertEquals(Message.RECONNECT_HANDSHAKE_VALUE, advice.get(Message.RECONNECT_FIELD));
    }

    @Test
    public void testSecondMetaConnectOnDifferentConnectionDoesNotLoseMessages() throws Exception {
        startServer(null);
        startClient();

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/cometd");
        BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        Session session1 = client.connect(new WebSocketEndPoint(messages), uri).get(5, TimeUnit.SECONDS);

        String handshake = "[{" +
                "\"id\":\"1\"," +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"websocket\"]" +
                "}]";
        session1.getRemote().sendString(handshake);

        Message handshakeReply = messages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(handshakeReply);
        Assertions.assertEquals(Channel.META_HANDSHAKE, handshakeReply.getChannel());
        Assertions.assertTrue(handshakeReply.isSuccessful());

        String testChannel = "/test";

        String clientId = handshakeReply.getClientId();
        String connect1 = "[{" +
                "\"id\":\"2\"," +
                "\"channel\":\"/meta/connect\"," +
                "\"connectionType\":\"websocket\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"advice\": {\"timeout\":0}" +
                "}, {" +
                "\"id\":\"3\"," +
                "\"channel\":\"/meta/subscribe\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"subscription\":[\"" + testChannel + "\"]" +
                "}]";
        session1.getRemote().sendString(connect1);

        Message connect1Reply = messages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(connect1Reply);
        Assertions.assertEquals(Channel.META_CONNECT, connect1Reply.getChannel());
        Assertions.assertTrue(connect1Reply.isSuccessful());
        Message subscribeReply = messages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(subscribeReply);
        Assertions.assertEquals(Channel.META_SUBSCRIBE, subscribeReply.getChannel());
        Assertions.assertTrue(subscribeReply.isSuccessful());

        // Disconnect and reconnect.
        disconnect(session1);
        Session session2 = client.connect(new WebSocketEndPoint(messages), uri).get(5, TimeUnit.SECONDS);
        String connect2 = "[{" +
                "\"id\":\"4\"," +
                "\"channel\":\"/meta/connect\"," +
                "\"connectionType\":\"websocket\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"advice\": {\"timeout\":0}" +
                "}]";
        session2.getRemote().sendString(connect2);

        Message connect2Reply = messages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(connect2Reply);
        Assertions.assertEquals(Channel.META_CONNECT, connect2Reply.getChannel());
        Assertions.assertTrue(connect2Reply.isSuccessful());

        // Emit a server-side message after receiving the second /meta/connect on a different connection.
        bayeux.getChannel(testChannel).publish(null, "test-data", Promise.noop());

        Message message = messages.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(message);
        Assertions.assertEquals(testChannel, message.getChannel());

        disconnect(session2);
    }

    private void disconnect(Session session) {
        try {
            session.disconnect();
        } catch (Throwable x) {
            x.printStackTrace();
        }
    }

    public class WebSocketEndPoint extends WebSocketAdapter {
        private final JSONContext.Client jsonContext = new JettyJSONContextClient();
        private final BlockingQueue<Message> messages;

        public WebSocketEndPoint(BlockingQueue<Message> messages) {
            this.messages = messages;
        }

        @Override
        public void onWebSocketText(String message) {
            try {
                Message.Mutable[] mutables = jsonContext.parse(message);
                Collections.addAll(messages, mutables);
            } catch (Throwable x) {
                disconnect(getSession());
            }
        }
    }
}
