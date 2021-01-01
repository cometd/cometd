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
package org.cometd.websocket.server;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
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
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class WebSocketTransportFailureTest {
    private Server server;
    private ServerConnector connector;
    private ServletContextHandler context;
    private HttpClient httpClient;
    private WebSocketClient client;
    private BayeuxServerImpl bayeux;

    @After
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

        context = new ServletContextHandler(server, "/");
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

        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        final JSONContext.Client jsonContext = new JettyJSONContextClient();
        final Session session = client.connect(new WebSocketAdapter() {
            @Override
            public void onWebSocketText(String message) {
                try {
                    Message.Mutable[] mutables = jsonContext.parse(message);
                    Collections.addAll(messages, mutables);
                } catch (Throwable x) {
                    disconnect(getSession());
                }
            }
        }, URI.create("ws://localhost:" + connector.getLocalPort() + "/cometd")).get(5, TimeUnit.SECONDS);

        String handshake = "[{" +
                "\"id\":\"1\"," +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"websocket\"]" +
                "}]";
        session.getRemote().sendString(handshake);

        Message message = messages.poll(5, TimeUnit.SECONDS);
        Assert.assertNotNull(message);
        Assert.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        Assert.assertTrue(message.isSuccessful());
        String clientId = message.getClientId();

        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                // Disconnect the client abruptly.
                disconnect(session);
                // Add messages for the client; the first message is written to
                // buffers, but the second message throws while trying to write it.
                from.deliver(null, "/foo", "bar1", Promise.noop());
                from.deliver(null, "/foo", "bar2", Promise.noop());
                return true;
            }
        });

        String connect = "[{" +
                "\"id\":\"2\"," +
                "\"channel\":\"/meta/connect\"," +
                "\"connectionType\":\"long-polling\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"advice\": {\"timeout\":0}" +
                "}]";
        session.getRemote().sendString(connect);

        // The session should expire on the server.
        Thread.sleep(2 * maxInterval);

        ServerSession serverSession = bayeux.getSession(clientId);
        Assert.assertNull(serverSession);

        ServerContainer container = (ServerContainer)context.getServletContext().getAttribute(javax.websocket.server.ServerContainer.class.getName());
        Assert.assertTrue(container.getOpenSessions().isEmpty());
    }

    private void disconnect(Session session) {
        try {
            session.disconnect();
        } catch (Throwable x) {
            x.printStackTrace();
        }
    }
}
