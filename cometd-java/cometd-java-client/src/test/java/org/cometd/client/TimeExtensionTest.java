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
import java.util.EventListener;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import junit.framework.TestCase;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.ext.TimesyncClientExtension;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.CometdServlet;
import org.cometd.server.ext.TimestampExtension;
import org.cometd.server.ext.TimesyncExtension;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.FileResource;

public class TimeExtensionTest extends TestCase
{
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

        ServletContextHandler context = new ServletContextHandler(contexts, "/", ServletContextHandler.SESSIONS);

        File file = new File("target/cometd-demo");
        if (!file.exists())
            assertTrue(file.mkdir());

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
        _bayeux.addExtension(new TimestampExtension());
        _bayeux.addExtension(new TimesyncExtension());
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

    public void testTimeStamp() throws Exception
    {
        int port = _connector.getLocalPort();
        assertTrue(port==_connector.getPort());

        final Queue<Message> messages = new ConcurrentLinkedQueue<Message>();

        final BayeuxClient client = new BayeuxClient("http://localhost:"+port+"/cometd", LongPollingTransport.create(null));
        client.addExtension(new TimesyncClientExtension());

        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messages.add(message);
            }
        });

        client.handshake();
        Thread.sleep(200);
        client.disconnect();
        Thread.sleep(200);

        assertTrue(messages.size()>0);

        for (Message message : messages)
            assertTrue(message.get("timestamp")!=null);


    }

    public void testTimeSync() throws Exception
    {
        int port = _connector.getLocalPort();
        assertTrue(port==_connector.getPort());

        final Queue<Message> messages = new ConcurrentLinkedQueue<Message>();

        final BayeuxClient client = new BayeuxClient("http://localhost:"+port+"/cometd", LongPollingTransport.create(null));

        client.addExtension(new TimesyncClientExtension());

        client.getChannel("/meta/*").addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messages.add(message);
            }
        });

        client.handshake();
        Thread.sleep(250);
        client.disconnect();
        Thread.sleep(250);

        assertTrue(messages.size()>0);

        // TODO: not all messages get the timesync extension if the server thinks it's accurate enough
//        for (Message message : messages)
//        {
//            Map<String,Object> ext = message.getExt();
//            assertNotNull(String.valueOf(message), ext);
//            assertNotNull(ext.get("timesync"));
//        }
    }
}
