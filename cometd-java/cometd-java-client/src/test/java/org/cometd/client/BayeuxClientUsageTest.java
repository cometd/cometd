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
import org.cometd.common.JacksonJSONContextClient;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.JacksonJSONContextServer;
import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.ProxyServlet;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class BayeuxClientUsageTest extends ClientServerTest
{
    @Test
    public void testClientWithSelectConnector() throws Exception
    {
        startServer(null);
        testClient(newBayeuxClient());
    }

    @Test
    public void testClientWithSocketConnector() throws Exception
    {
        startServer(null);
        httpClient.stop();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        httpClient.start();
        testClient(newBayeuxClient());
    }

    @Test
    public void testClientWithJackson() throws Exception
    {
        Map<String, String> serverOptions = new HashMap<String, String>();
        serverOptions.put(BayeuxServerImpl.JSON_CONTEXT, JacksonJSONContextServer.class.getName());
        startServer(serverOptions);

        Map<String, Object> clientOptions = new HashMap<String, Object>();
        clientOptions.put(ClientTransport.JSON_CONTEXT, new JacksonJSONContextClient());
        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(clientOptions, httpClient));

        testClient(client);
    }

    @Test
    public void testClientWithProxy() throws Exception
    {
        startServer(null);

        Server proxy = new Server();
        Connector proxyConnector = new SelectChannelConnector();
        proxy.addConnector(proxyConnector);

        ServletContextHandler context = new ServletContextHandler(proxy, "/", ServletContextHandler.SESSIONS);
        ServletHolder proxyServlet = new ServletHolder(ProxyServlet.class);
        context.addServlet(proxyServlet, "/*");

        proxy.start();
        httpClient.setProxy(new Address("localhost", proxyConnector.getLocalPort()));

        testClient(newBayeuxClient());
    }

    @Ignore // TODO: un-ignore when using Jetty 7.6.12 (which fixes https://bugs.eclipse.org/bugs/show_bug.cgi?id=411135)
    @Test
    public void testClientWithProxyTunnel() throws Exception
    {
        startServer(null);

        SslContextFactory sslContextFactory = new SslContextFactory();
        File keyStoreFile = new File("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePath(keyStoreFile.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");
        SslSelectChannelConnector sslConnector = new SslSelectChannelConnector(sslContextFactory);
        server.addConnector(sslConnector);
        sslConnector.start();

        Server proxy = new Server();
        Connector proxyConnector = new SelectChannelConnector();
        proxy.addConnector(proxyConnector);

        ConnectHandler connectHandler = new ConnectHandler();
        proxy.setHandler(connectHandler);

        proxy.start();
        httpClient.setProxy(new Address("localhost", proxyConnector.getLocalPort()));

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

        final BlockingQueue<Message> metaMessages = new ArrayBlockingQueue<Message>(16);
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

        final BlockingQueue<Message> messages = new ArrayBlockingQueue<Message>(16);
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
