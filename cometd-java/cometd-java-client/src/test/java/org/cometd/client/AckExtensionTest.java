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

import java.io.File;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import junit.framework.TestCase;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.SessionChannel;
import org.cometd.bayeux.client.SessionChannel.SubscriptionListener;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.client.ext.AckExtension;
import org.cometd.server.CometdServlet;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.FileResource;

public class AckExtensionTest extends TestCase
{
    public static int RECEIVE_LOCK_DURATION = Integer.getInteger(
            "receive.lock_duration", 2000).intValue();

    Server _server;
    Connector _connector;
    EventListener _listener;
    BayeuxServer _bayeux;

    static Connector newConnector()
    {
        return new SelectChannelConnector();
    }

    static Server newServer(Connector connector) throws Exception
    {
        Server server = new Server();
        server.setGracefulShutdown(500);
        connector.setPort(0);
        server.setConnectors(new Connector[] { connector });
        return server;
    }

    static void stopServer(Server server) throws Exception
    {
        server.stop();
    }

    void startServer(Server server)
            throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        
        ServletContextHandler context = new ServletContextHandler(contexts, "/", ServletContextHandler.NO_SECURITY
                | ServletContextHandler.SESSIONS);

        File file = new File("target/cometd-demo");
        if (!file.exists())
            file.mkdir();

        context.setBaseResource(FileResource.newResource(file.toURI().toURL()));

        ServletHolder comet = context.addServlet(CometdServlet.class, "/cometd/*");

        comet.setInitParameter("timeout", "20000");
        comet.setInitParameter("interval", "100");
        comet.setInitParameter("maxInterval", "10000");
        comet.setInitParameter("multiFrameInterval", "5000");
        comet.setInitParameter("logLevel", "3");
        comet.setInitOrder(2);

        server.start();
        
        _bayeux=(BayeuxServer)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        _bayeux.addExtension(new AcknowledgedMessagesExtension());
    }

    @Override
    public void setUp() throws Exception
    {
        startServer(_server = newServer(_connector = newConnector()));
        _connector.setPort(_connector.getLocalPort());
        super.setUp();
    }

    @Override
    public void tearDown() throws Exception
    {
        stopServer(_server);
        _listener = null;
        _bayeux = null;
        _connector = null;
        _server = null;
        super.tearDown();
    }

    public void testAck() throws Exception
    {
        int port = _connector.getLocalPort();
        assertTrue(port==_connector.getPort());

        final Queue<Message> messages = new ConcurrentLinkedQueue<Message>();
        
        final BayeuxClient client = new BayeuxClient("http://localhost:"+port+"/cometd");
        client.addExtension(new AckExtension());
        
        client.getChannel(Channel.META_HANDSHAKE).addListener(new SessionChannel.MetaChannelListener()
        {
            @Override
            public void onMetaMessage(SessionChannel channel, Message message, boolean successful, String error)
            {                
                if (successful)
                {
                    client.getChannel("/chat/demo").subscribe(new SubscriptionListener()
                    {
                        @Override
                        public void onMessage(SessionChannel channel, Message message)
                        {
                            messages.add(message);
                        }
                    });
                }
            }
        });
        
        client.handshake();

        Thread.sleep(500);

        assertEquals(0,messages.size());

        ServerChannel publicChat = _bayeux.getChannel("/chat/demo", true);
        assertTrue(publicChat != null);
        
        for(int i=0; i<5;i++)
        {
            publicChat.publish(null,"hello","id"+i);
            Thread.sleep(20);
        }

        Thread.sleep(500);
        assertEquals(5,messages.size());
        
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
        assertEquals(5,messages.size());
        
        
        _connector.start();
        // allow a few secs for the client to reconnect
        Thread.sleep(4000);
        assertTrue(_connector.isStarted());

        // check that the offline messages are received
        assertEquals(10,messages.size());
        
        // send messages while client is online
        for(int i=10; i<15;i++)
            publicChat.publish(null,"hello","id"+i);

        Thread.sleep(500);
        
        // check if messages after reconnect are received 
        System.err.println(messages);
        for(int i=0; i<15;i++)
        {
            Message message = messages.poll();
            assertTrue(message.getId().toString().indexOf("id"+i)>=0);
        }
        
        client.disconnect();
    }




}
