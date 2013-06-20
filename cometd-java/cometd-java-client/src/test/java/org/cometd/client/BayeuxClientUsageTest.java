/*
 * Copyright (c) 2011 the original author or authors.
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

package org.cometd.client;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.Jackson1JSONContextClient;
import org.cometd.common.Jackson2JSONContextClient;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.Jackson1JSONContextServer;
import org.cometd.server.Jackson2JSONContextServer;
import org.cometd.server.JettyJSONContextServer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ProxyConfiguration;
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
public class BayeuxClientUsageTest extends ClientServerTest
{
    @Parameters(name= "{index}: JSON Context Server: {0} JSON Context Client: {1}")
     public static Iterable<Object[]> data()
     {
         return Arrays.asList(new Object[][]
                 {
                         {Jackson2JSONContextServer.class, Jackson2JSONContextClient.class},
                         {Jackson1JSONContextServer.class, Jackson1JSONContextClient.class},
                         {JettyJSONContextServer.class, JettyJSONContextClient.class}
                 }
         );
     }

    private final String jacksonContextServerClassName;
    private final String jacksonContextClientClassName;

    public BayeuxClientUsageTest(final Class<?> jacksonContextServerClass, final Class<?> jacksonContextClientClass)
    {
        this.jacksonContextServerClassName = jacksonContextServerClass.getName();
        this.jacksonContextClientClassName = jacksonContextClientClass.getName();
    }

    @Test
    public void testClientWithSelectConnector() throws Exception
    {
        startServer(null);
        testClient(newBayeuxClient());
    }

    @Test
    public void testClientWithJackson() throws Exception
    {
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put(BayeuxServerImpl.JSON_CONTEXT, jacksonContextServerClassName);
        startServer(serverOptions);

        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put(ClientTransport.JSON_CONTEXT, jacksonContextClientClassName);
        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(clientOptions, httpClient));

        testClient(client);
    }

    @Test
    public void testClientWithProxy() throws Exception
    {
        startServer(null);

        Server proxy = new Server();
        ServerConnector proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);

        ServletContextHandler context = new ServletContextHandler(proxy, "/", ServletContextHandler.SESSIONS);
        ServletHolder proxyServlet = new ServletHolder(ProxyServlet.class);
        context.addServlet(proxyServlet, "/*");

        proxy.start();
        httpClient.setProxyConfiguration(new ProxyConfiguration("localhost", proxyConnector.getLocalPort()));

        testClient(newBayeuxClient());
    }

    @Test
    public void testClientWithProxyTunnel() throws Exception
    {
        startServer(null);

        SslContextFactory sslContextFactory = new SslContextFactory();
        File keyStoreFile = new File("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePath(keyStoreFile.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");
        ServerConnector sslConnector = new ServerConnector(server, sslContextFactory);
        server.addConnector(sslConnector);
        sslConnector.start();

        Server proxy = new Server();
        ServerConnector proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);

        ConnectHandler connectHandler = new ConnectHandler();
        proxy.setHandler(connectHandler);

        proxy.start();
        httpClient.stop();
        httpClient = new HttpClient(sslContextFactory);
        httpClient.setProxyConfiguration(new ProxyConfiguration("localhost", proxyConnector.getLocalPort()));
        httpClient.start();

        String url = "https://localhost:" + sslConnector.getLocalPort() + cometdServletPath;
        BayeuxClient client = new BayeuxClient(url, new LongPollingTransport(null, httpClient));
        testClient(client);
    }

    private void testClient(BayeuxClient client) throws Exception
    {
        client.setDebugEnabled(debugTests());

        final AtomicBoolean connected = new AtomicBoolean();
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(message.isSuccessful());
            }
        });

        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(false);
            }
        });

        final BlockingQueue<Message> metaMessages = new ArrayBlockingQueue<>(16);
        client.getChannel("/meta/*").addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                metaMessages.offer(message);
            }
        });

        client.handshake();

        Message message = metaMessages.poll(1, TimeUnit.SECONDS);
        Assert.assertNotNull(message);
        Assert.assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        Assert.assertTrue(message.isSuccessful());
        String id = client.getId();
        Assert.assertNotNull(id);

        message = metaMessages.poll(1, TimeUnit.SECONDS);
        Assert.assertEquals(Channel.META_CONNECT, message.getChannel());
        Assert.assertTrue(message.isSuccessful());

        final BlockingQueue<Message> messages = new ArrayBlockingQueue<>(16);
        ClientSessionChannel.MessageListener subscriber = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messages.offer(message);
            }
        };
        ClientSessionChannel aChannel = client.getChannel("/a/channel");
        aChannel.subscribe(subscriber);

        message = metaMessages.poll(1, TimeUnit.SECONDS);
        Assert.assertEquals(Channel.META_SUBSCRIBE, message.getChannel());
        Assert.assertTrue(message.isSuccessful());

        String data = "data";
        aChannel.publish(data);
        message = messages.poll(1, TimeUnit.SECONDS);
        Assert.assertEquals(data, message.getData());

        aChannel.unsubscribe(subscriber);
        message = metaMessages.poll(1, TimeUnit.SECONDS);
        Assert.assertEquals(Channel.META_UNSUBSCRIBE, message.getChannel());
        Assert.assertTrue(message.isSuccessful());

        disconnectBayeuxClient(client);
    }
}
