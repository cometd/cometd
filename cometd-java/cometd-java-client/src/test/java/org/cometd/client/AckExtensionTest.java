//========================================================================
// Copyright 2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.cometd.client;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.client.BayeuxClient.State;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.CometdServlet;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.log.Log;

public class AckExtensionTest extends TestCase
{
    private Server _server;
    private Connector _connector;
    private BayeuxServer _bayeux;
    private String _cometdURL;

    private static Connector newConnector()
    {
        return new SelectChannelConnector();
    }

    private static Server newServer(Connector connector) throws Exception
    {
        Server server = new Server();
        server.setGracefulShutdown(500);
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });
        return server;
    }

    private void startServer(Server server) throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(contexts, contextPath, ServletContextHandler.SESSIONS);

        String servletPath = "/cometd";
        ServletHolder comet = context.addServlet(CometdServlet.class, servletPath + "/*");

        comet.setInitParameter("timeout", "20000");
        comet.setInitParameter("interval", "100");
        comet.setInitParameter("maxInterval", "10000");
        comet.setInitParameter("multiFrameInterval", "5000");
        comet.setInitParameter("logLevel", "3");
        comet.setInitOrder(1);

        server.start();

        _bayeux=(BayeuxServer)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        _bayeux.addExtension(new AcknowledgedMessagesExtension());

        _cometdURL = "http://localhost:" + _connector.getLocalPort() + contextPath + servletPath;
    }

    private static void stopServer(Server server) throws Exception
    {
        server.stop();
        server.join();
    }

    @Override
    public void setUp() throws Exception
    {
        startServer(_server = newServer(_connector = newConnector()));
        _connector.setPort(_connector.getLocalPort());
    }

    @Override
    public void tearDown() throws Exception
    {
        stopServer(_server);
        _bayeux = null;
        _connector = null;
        _server = null;
    }

    public void testAck() throws Exception
    {
        int port = _connector.getLocalPort();
        assertTrue(port==_connector.getPort());

        final BlockingQueue<Message> messages = new BlockingArrayQueue<Message>();

        final BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null))
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                Log.info(x.toString());
            }
            
        };

        client.addExtension(new AckExtension());

        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                {
                    client.getChannel("/chat/demo").subscribe(new ClientSessionChannel.MessageListener()
                    {
                        public void onMessage(ClientSessionChannel channel, Message message)
                        {
                            messages.add(message);
                        }
                    });
                }
            }
        });

        final CountDownLatch subscribed=new CountDownLatch(1);
        _bayeux.addListener(new BayeuxServer.ChannelListener()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
            }
            
            public void channelRemoved(String channelId)
            {
            }
            
            public void channelAdded(ServerChannel channel)
            {
                if ("/chat/demo".equals(channel.getId()))
                    subscribed.countDown();
            }
        });

        client.handshake();

        assertTrue(subscribed.await(10,TimeUnit.SECONDS));
        assertEquals(0,messages.size());

        ServerChannel publicChat = _bayeux.getChannel("/chat/demo");
        assertNotNull(publicChat);

        for(int i=0; i<5;i++)
        {
            publicChat.publish(null,"hello","id"+i);
            Thread.sleep(20);
        }

        for(int i=0; i<5;i++)
            assertEquals("id"+i,messages.poll(5,TimeUnit.SECONDS).getId());

        _connector.stop();
        Thread.sleep(100);
        assertTrue(_connector.isStopped());

        // send messages while client is offline
        for(int i=5; i<10;i++)
        {
            publicChat.publish(null,"hello","id"+i);
            Thread.sleep(20);
        }

        Thread.sleep(500);
        assertEquals(0,messages.size());


        _connector.start();
        assertTrue(_connector.isStarted());
        
        // check that the offline messages are received
        for(int i=5; i<10;i++)
            assertEquals("id"+i,messages.poll(5,TimeUnit.SECONDS).getId());

        // send messages while client is online
        for(int i=10; i<15;i++)
        {
            publicChat.publish(null,"hello","id"+i);
            Thread.sleep(500);
        }


        // check if messages after reconnect are received
        for(int i=10; i<15;i++)
            assertEquals("id"+i,messages.poll(5,TimeUnit.SECONDS).getId());

        client.disconnect();
        assertTrue(client.waitFor(1000L,State.DISCONNECTED));
    }
}
