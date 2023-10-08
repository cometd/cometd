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
package org.cometd.tests;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerMessageImpl;
import org.cometd.server.http.jakarta.CometDServlet;
import org.cometd.server.http.jakarta.transport.ServletJSONTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ProxyTest {
    private Server server;
    private ServerConnector serverConnector;
    private String serverCometDURL;
    private BayeuxServer serverBayeux;
    private Server proxy;
    private ServerConnector proxyConnector;
    private String proxyCometDURL;
    private BayeuxServer proxyBayeux;
    private String cometdServletPath = "/cometd";
    private HttpClient httpClient;
    private HttpClient proxyHttpClient;

    protected void startServer(Map<String, String> initParams) throws Exception {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        serverConnector = new ServerConnector(server);
        serverConnector.setIdleTimeout(30000);
        server.addConnector(serverConnector);
        ServletContextHandler context = prepareContext(server, initParams);
        server.start();
        int port = serverConnector.getLocalPort();
        serverCometDURL = "http://localhost:" + port + cometdServletPath;
        serverBayeux = (BayeuxServer)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
    }

    protected void startProxy(Map<String, String> initParams) throws Exception {
        QueuedThreadPool proxyThreads = new QueuedThreadPool();
        proxyThreads.setName("proxy");
        proxy = new Server(proxyThreads);
        proxyConnector = new ServerConnector(proxy);
        proxyConnector.setIdleTimeout(30000);
        proxy.addConnector(proxyConnector);
        ServletContextHandler context = prepareContext(proxy, initParams);
        proxy.start();
        int port = proxyConnector.getLocalPort();
        proxyCometDURL = "http://localhost:" + port + cometdServletPath;
        proxyBayeux = (BayeuxServer)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("proxy");
        proxyHttpClient = new HttpClient();
        proxyHttpClient.setExecutor(clientThreads);
        proxyHttpClient.start();
    }

    private ServletContextHandler prepareContext(Server server, Map<String, String> initParams) {
        ServletContextHandler context = new ServletContextHandler("/");
        server.setHandler(context);
        // CometD servlet
        ServletHolder cometdServletHolder = new ServletHolder(CometDServlet.class);
        cometdServletHolder.setInitParameter(BayeuxServerImpl.TRANSPORTS_OPTION, ServletJSONTransport.class.getName());
        cometdServletHolder.setInitParameter(AbstractServerTransport.TIMEOUT_OPTION, "10000");
        cometdServletHolder.setInitOrder(1);
        if (initParams != null) {
            for (Map.Entry<String, String> entry : initParams.entrySet()) {
                cometdServletHolder.setInitParameter(entry.getKey(), entry.getValue());
            }
        }
        context.addServlet(cometdServletHolder, cometdServletPath + "/*");
        return context;
    }

    protected void startClient() throws Exception {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        httpClient = new HttpClient();
        httpClient.setExecutor(clientThreads);
        httpClient.start();
    }

    @AfterEach
    public void dispose() throws Exception {
        if (httpClient != null) {
            httpClient.stop();
        }
        if (proxyHttpClient != null) {
            proxyHttpClient.stop();
        }
        if (proxy != null) {
            proxy.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void testHandshakeDisconnect() throws Exception {
        startServer(null);
        startProxy(null);
        startClient();

        ReverseProxyExtension extension = new ReverseProxyExtension(serverCometDURL, new JettyHttpClientTransport.Factory(proxyHttpClient));
        proxyBayeux.addExtension(extension);

        CountDownLatch handshakeLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(proxyCometDURL, new JettyHttpClientTransport(null, httpClient));
        client.handshake(message -> {
            if (message.isSuccessful()) {
                handshakeLatch.countDown();
            }
        });

        Assertions.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(1, extension.sessions.size());
        BayeuxClient proxy = extension.sessions.values().iterator().next();
        Assertions.assertTrue(proxy.isHandshook());
        Assertions.assertNotEquals(client.getId(), proxy.getId());

        CountDownLatch proxyDisconnectLatch = new CountDownLatch(1);
        proxy.getChannel(Channel.META_DISCONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> proxyDisconnectLatch.countDown());

        CountDownLatch disconnectLatch = new CountDownLatch(1);
        client.disconnect(message -> disconnectLatch.countDown());

        Assertions.assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(0, extension.sessions.size());

        Assertions.assertTrue(proxyDisconnectLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSubscribePublishUnsubscribe() throws Exception {
        startServer(null);
        startProxy(null);
        startClient();

        ReverseProxyExtension extension = new ReverseProxyExtension(serverCometDURL, new JettyHttpClientTransport.Factory(proxyHttpClient));
        proxyBayeux.addExtension(extension);

        String channelName = "/foo";
        BayeuxClient client = new BayeuxClient(proxyCometDURL, new JettyHttpClientTransport(null, httpClient));
        ClientSessionChannel clientChannel = client.getChannel(channelName);

        AtomicReference<CountDownLatch> messageLatch = new AtomicReference<>(new CountDownLatch(1));
        ClientSessionChannel.MessageListener messageListener = (c, m) -> messageLatch.get().countDown();

        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        client.handshake(h -> {
            if (h.isSuccessful()) {
                clientChannel.subscribe(messageListener, r -> {
                    if (r.isSuccessful()) {
                        subscriptionLatch.countDown();
                    }
                });
            }
        });

        Assertions.assertTrue(subscriptionLatch.await(5, TimeUnit.SECONDS));

        // Publish from the client.
        CountDownLatch publishLatch = new CountDownLatch(1);
        clientChannel.publish("data_from_client", r -> {
            if (r.isSuccessful()) {
                publishLatch.countDown();
            }
        });

        Assertions.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(messageLatch.get().await(5, TimeUnit.SECONDS));

        // Publish from the server.
        messageLatch.set(new CountDownLatch(1));
        ServerChannel serverChannel = serverBayeux.getChannel(channelName);
        serverChannel.publish(null, "data_from_server", Promise.noop());

        Assertions.assertTrue(messageLatch.get().await(5, TimeUnit.SECONDS));

        // Publish from the proxy should fail.
        messageLatch.set(new CountDownLatch(1));
        ServerChannel proxyChannel = proxyBayeux.createChannelIfAbsent(channelName).getReference();
        proxyChannel.publish(null, "data_from_proxy", Promise.noop());

        Assertions.assertFalse(messageLatch.get().await(1, TimeUnit.SECONDS));

        CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        clientChannel.unsubscribe(messageListener, r -> {
            if (r.isSuccessful()) {
                unsubscribeLatch.countDown();
            }
        });

        Assertions.assertTrue(unsubscribeLatch.await(5, TimeUnit.SECONDS));

        // After unsubscription, messages are not relayed.
        messageLatch.set(new CountDownLatch(1));
        clientChannel.publish("data_from_client_2");
        proxyBayeux.createChannelIfAbsent(channelName).getReference().publish(null, "data_from_proxy_2", Promise.noop());
        serverChannel.publish(null, "data_from_server_2", Promise.noop());

        Assertions.assertFalse(messageLatch.get().await(1, TimeUnit.SECONDS));

        client.disconnect();
    }

    private static class ReverseProxyExtension implements BayeuxServer.Extension {
        private final Map<ServerSession, BayeuxClient> sessions = new ConcurrentHashMap<>();
        private final Map<BayeuxClient, Map<String, ClientSessionChannel.MessageListener>> subscriptions = new ConcurrentHashMap<>();
        private final String serverCometDURL;
        private final ClientTransport.Factory transportFactory;

        public ReverseProxyExtension(String serverCometDURL, ClientTransport.Factory transportFactory) {
            this.serverCometDURL = serverCometDURL;
            this.transportFactory = transportFactory;
        }

        @Override
        public void incoming(ServerSession session, ServerMessage.Mutable message, Promise<Boolean> promise) {
            String channel = message.getChannel();
            if (Channel.META_HANDSHAKE.equals(channel)) {
                BayeuxClient client = new BayeuxClient(serverCometDURL, transportFactory.newClientTransport(null, null));
                session.addListener((ServerSession.AddedListener)(s, m) -> sessions.put(s, client));
                session.addListener((ServerSession.RemovedListener)(s, m, t) -> {
                    sessions.remove(s);
                    client.disconnect();
                });
                subscriptions.put(client, new ConcurrentHashMap<>());
                client.handshake(handshake -> {
                    // TODO: this should be a once only code, but it'll be
                    // TODO: executed again if the server decides to re-handshake.
                    ServerMessage.Mutable reply = message.getAssociated();
                    reply.setSuccessful(handshake.isSuccessful());
                    promise.succeed(true);
                });
            } else if (Channel.META_CONNECT.equals(channel)) {
                // /meta/connect are hop only, so don't proxy them.
                promise.succeed(true);
            } else if (Channel.META_SUBSCRIBE.equals(channel)) {
                BayeuxClient client = sessions.get(session);
                if (client != null) {
                    // TODO: handle String[]
                    String subscription = (String)message.get(Message.SUBSCRIPTION_FIELD);
                    ClientSessionChannel.MessageListener listener = (c, m) -> {
                        session.deliver(client, c.getId(), m.getData(), Promise.noop());
                    };
                    subscriptions.get(client).put(subscription, listener);
                    client.getChannel(subscription).subscribe(listener, r -> {
                        ServerMessage.Mutable reply = message.getAssociated();
                        reply.setSuccessful(r.isSuccessful());
                        reply.put(Message.SUBSCRIPTION_FIELD, r.get(Message.SUBSCRIPTION_FIELD));
                        ((ServerMessageImpl)reply).setHandled(true);
                        promise.succeed(false);
                    });
                } else {
                    // TODO: modify the reply with a 503-like error.
                    promise.succeed(true);
                }
            } else if (Channel.META_UNSUBSCRIBE.equals(channel)) {
                BayeuxClient client = sessions.get(session);
                if (client != null) {
                    // TODO: handle String[]
                    String subscription = (String)message.get(Message.SUBSCRIPTION_FIELD);
                    ClientSessionChannel.MessageListener listener = subscriptions.get(client).remove(subscription);
                    client.getChannel(subscription).unsubscribe(listener, r -> {
                        ServerMessage.Mutable reply = message.getAssociated();
                        reply.setSuccessful(r.isSuccessful());
                        reply.put(Message.SUBSCRIPTION_FIELD, r.get(Message.SUBSCRIPTION_FIELD));
                        ((ServerMessageImpl)reply).setHandled(true);
                        promise.succeed(false);
                    });
                } else {
                    // TODO: modify the reply with a 503-like error.
                    promise.succeed(true);
                }
            } else if (Channel.META_DISCONNECT.equals(channel)) {
                BayeuxClient client = sessions.get(session);
                if (client != null) {
                    client.disconnect(r -> {
                        ServerMessage.Mutable reply = message.getAssociated();
                        reply.setSuccessful(r.isSuccessful());
                        promise.succeed(true);
                    });
                } else {
                    promise.succeed(true);
                }
            } else {
                BayeuxClient client = sessions.get(session);
                if (client != null) {
                    client.getChannel(channel).publish(message, r -> {
                        ServerMessage.Mutable reply = message.getAssociated();
                        reply.setSuccessful(r.isSuccessful());
                        ((ServerMessageImpl)reply).setHandled(true);
                        promise.succeed(false);
                    });
                } else {
                    // TODO: modify the reply with a 503-like error.
                    promise.succeed(true);
                }
            }
        }
    }
}
