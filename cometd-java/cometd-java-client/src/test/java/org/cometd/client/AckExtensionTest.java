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

import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import junit.framework.TestCase;

import org.cometd.Bayeux;
import org.cometd.Channel;
import org.cometd.client.ChatRoomClient.Msg;
import org.cometd.client.ext.AckExtension;
import org.cometd.server.continuation.ContinuationCometdServlet;
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
    Bayeux _bayeux;
    ChatService _chatService;

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

    static void startServer(Server server, EventListener listener)
            throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        
        ServletContextHandler context = new ServletContextHandler(contexts, "/cometd", ServletContextHandler.NO_SECURITY
                | ServletContextHandler.SESSIONS);

        File file = new File("target/cometd-demo");
        if (!file.exists())
            file.mkdir();

        context.setBaseResource(FileResource.newResource(file.toURI().toURL()));

        ServletHolder comet = context.addServlet(
                ContinuationCometdServlet.class, "/cometd/*");

        comet.setInitParameter("timeout", "20000");
        comet.setInitParameter("interval", "100");
        comet.setInitParameter("maxInterval", "10000");
        comet.setInitParameter("multiFrameInterval", "5000");
        comet.setInitParameter("logLevel", "0");
        comet.setInitOrder(2);

        context.addEventListener(listener);

        server.start();
    }

    public void setUp() throws Exception
    {
        startServer(_server = newServer(_connector = newConnector()),
                _listener = newContextAttributeListener());
        _connector.setPort(_connector.getLocalPort());
        super.setUp();
    }

    public void tearDown() throws Exception
    {
        stopServer(_server);
        _listener = null;
        _bayeux = null;
        _connector = null;
        _server = null;
        _chatService = null;
        super.tearDown();
    }

    public void testAck() throws Exception
    {
        int port = _connector.getLocalPort();
        System.err.println("port: " + _connector.getPort() + " " + port + " " + _bayeux);
        assertTrue(port==_connector.getPort());

        final Object lock = new Object();
        final List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        ChatRoomClient room = new ChatRoomClient(port)
        {
            public void onPublicMessageReceived(org.cometd.Client from,
                    Map<String, Object> message)
            {
                synchronized (lock)
                {
                    messages.add(message);
                    lock.notify();
                }
                System.err.println(message);
            }
        };
        room.addExtension(new AckExtension());
        room.start();

        Thread.sleep(500);

        room.join("foo");

        int expectedMessages = 0;
        // foo has joined
        assertTrue(receive(lock, messages, ++expectedMessages));

        Channel publicChat = _bayeux.getChannel("/chat/demo", false);
        assertTrue(publicChat != null);
        
        for(int i=0; i<5;)
        {
            publishFromServer(publicChat, "server", "message_while_connected_" + (++i));
            assertTrue(receive(lock, messages, ++expectedMessages));
        }

        _connector.stop();
        Thread.sleep(1000);
        assertTrue(_connector.isStopped());

        // send messages while client is offline
        for(int i=0; i<5;)
        {
            publishFromServer(publicChat, "server", "message_while_disconnected_" + (++i));
            ++expectedMessages;
        }
        // verify that the offline messages are not received
        assertTrue(receive(lock, messages, expectedMessages-5));
        
        _connector.start();
        // allow a few secs for the client to reconnect
        Thread.sleep(3000);
        assertTrue(_connector.isStarted());

        // check that the offline messages are received
        assertTrue(receive(lock, messages, expectedMessages));

        // check if messages after reconnect are received 
        for(int i=0; i<5;)
        {
            publishFromServer(publicChat, "server", "message_after_reconnect_" + (++i));
            assertTrue(receive(lock, messages, ++expectedMessages));
        }
        
        synchronized(lock)
        {
            assertTrue(messages.size()==16 && expectedMessages==16);
            assertEquals("foo has joined", messages.get(0).get("chat"));
            assertEquals("message_while_connected_1", messages.get(1).get("chat"));
            assertEquals("message_while_connected_2", messages.get(2).get("chat"));
            assertEquals("message_while_connected_3", messages.get(3).get("chat"));
            assertEquals("message_while_connected_4", messages.get(4).get("chat"));
            assertEquals("message_while_connected_5", messages.get(5).get("chat"));
            
            assertEquals("message_while_disconnected_1", messages.get(6).get("chat"));
            assertEquals("message_while_disconnected_2", messages.get(7).get("chat"));
            assertEquals("message_while_disconnected_3", messages.get(8).get("chat"));
            assertEquals("message_while_disconnected_4", messages.get(9).get("chat"));
            assertEquals("message_while_disconnected_5", messages.get(10).get("chat"));
            
            assertEquals("message_after_reconnect_1", messages.get(11).get("chat"));
            assertEquals("message_after_reconnect_2", messages.get(12).get("chat"));
            assertEquals("message_after_reconnect_3", messages.get(13).get("chat"));
            assertEquals("message_after_reconnect_4", messages.get(14).get("chat"));
            assertEquals("message_after_reconnect_5", messages.get(15).get("chat"));
        }

        room.stop();
    }

    protected boolean receive(Object lock, List<Map<String, Object>> messages,
            int expectedMessages) throws Exception
    {
        synchronized (lock)
        {
            lock.wait(RECEIVE_LOCK_DURATION);
            return expectedMessages == messages.size();
        }
    }

    protected void publishFromServer(Channel channel, String user, String chat)
    {
        channel.publish(_chatService.getClient(), new Msg().add("user", user)
                .add("chat", chat), null);
    }

    protected ServletContextAttributeListener newContextAttributeListener()
    {
        return new ContextListener();
    }

    public class ContextListener implements ServletContextListener,
            ServletContextAttributeListener
    {

        public void contextDestroyed(ServletContextEvent event)
        {

        }

        public void contextInitialized(ServletContextEvent event)
        {

        }

        public void attributeAdded(ServletContextAttributeEvent event)
        {
            if (_bayeux == null && event.getName().equals(Bayeux.ATTRIBUTE))
            {
                _bayeux = (Bayeux) event.getValue();
                _chatService = new ChatService(_bayeux);
            }
        }

        public void attributeRemoved(ServletContextAttributeEvent event)
        {

        }

        public void attributeReplaced(ServletContextAttributeEvent event)
        {

        }

    }

}
