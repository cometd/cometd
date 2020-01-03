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

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.JacksonJSONContextClient;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.JacksonJSONContextServer;
import org.cometd.server.JettyJSONContextServer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class BayeuxClientUsageTest extends ClientServerTest {
    @Parameters(name = "{index}: JSON Context Server: {0} JSON Context Client: {1}")
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][]
                {
                        {JacksonJSONContextServer.class, JacksonJSONContextClient.class},
                        {JettyJSONContextServer.class, JettyJSONContextClient.class}
                }
        );
    }

    private final String jacksonContextServerClassName;
    private final String jacksonContextClientClassName;

    public BayeuxClientUsageTest(final Class<?> jacksonContextServerClass, final Class<?> jacksonContextClientClass) {
        this.jacksonContextServerClassName = jacksonContextServerClass.getName();
        this.jacksonContextClientClassName = jacksonContextClientClass.getName();
    }

    @Test
    public void testClientWithSelectConnector() throws Exception {
        start(null);
        testClient(newBayeuxClient());
    }

    @Test
    public void testClientWithJackson() throws Exception {
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put(AbstractServerTransport.JSON_CONTEXT_OPTION, jacksonContextServerClassName);
        start(serverOptions);

        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put(ClientTransport.JSON_CONTEXT_OPTION, jacksonContextClientClassName);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(clientOptions, httpClient));

        testClient(client);
    }

    @Test
    public void testClientWithProxy() throws Exception {
        start(null);

        Server proxy = new Server();
        ServerConnector proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);

        ServletContextHandler context = new ServletContextHandler(proxy, "/", ServletContextHandler.SESSIONS);
        ServletHolder proxyServlet = new ServletHolder(ProxyServlet.class);
        context.addServlet(proxyServlet, "/*");

        proxy.start();
        httpClient.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyConnector.getLocalPort()));

        testClient(newBayeuxClient());
    }

    @Test
    public void testClientWithProxyTunnel() throws Exception {
        start(null);

        SslContextFactory.Server sslServer = new SslContextFactory.Server();
        File keyStoreFile = new File("src/test/resources/keystore.p12");
        sslServer.setKeyStorePath(keyStoreFile.getAbsolutePath());
        sslServer.setKeyStoreType("pkcs12");
        sslServer.setKeyStorePassword("storepwd");
        ServerConnector sslConnector = new ServerConnector(server, sslServer);
        server.addConnector(sslConnector);
        sslConnector.start();

        Server proxy = new Server();
        ServerConnector proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);
        ConnectHandler connectHandler = new ConnectHandler();
        proxy.setHandler(connectHandler);
        proxy.start();

        SslContextFactory.Client sslClient = new SslContextFactory.Client(true);
        sslServer.setKeyStorePath(keyStoreFile.getAbsolutePath());
        sslServer.setKeyStoreType("pkcs12");
        sslServer.setKeyStorePassword("storepwd");
        httpClient.stop();
        httpClient = new HttpClient(sslClient);
        httpClient.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyConnector.getLocalPort()));
        httpClient.start();

        String url = "https://localhost:" + sslConnector.getLocalPort() + cometdServletPath;
        BayeuxClient client = new BayeuxClient(url, new JettyHttpClientTransport(null, httpClient));
        testClient(client);
    }

    private void testClient(BayeuxClient client) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                latch.countDown();
            }
        });

        final BlockingQueue<Message> metaMessages = new ArrayBlockingQueue<>(16);
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
        Assert.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        Assert.assertTrue(message.isSuccessful());
        String id = client.getId();
        Assert.assertNotNull(id);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        final BlockingQueue<Message> messages = new ArrayBlockingQueue<>(16);
        ClientSessionChannel.MessageListener subscriber = (c, m) -> messages.offer(m);
        ClientSessionChannel aChannel = client.getChannel("/a/channel");
        aChannel.subscribe(subscriber);

        message = metaMessages.poll(5, TimeUnit.SECONDS);
        Assert.assertEquals(Channel.META_SUBSCRIBE, message.getChannel());
        Assert.assertTrue(message.isSuccessful());

        String data = "data";
        aChannel.publish(data);
        message = messages.poll(5, TimeUnit.SECONDS);
        Assert.assertEquals(data, message.getData());

        aChannel.unsubscribe(subscriber);
        message = metaMessages.poll(5, TimeUnit.SECONDS);
        Assert.assertEquals(Channel.META_UNSUBSCRIBE, message.getChannel());
        Assert.assertTrue(message.isSuccessful());

        disconnectBayeuxClient(client);
    }
}
