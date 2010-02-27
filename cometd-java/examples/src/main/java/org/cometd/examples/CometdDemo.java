// ========================================================================
// Copyright 2007 Mort Bay Consulting Pty. Ltd.
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

package org.cometd.examples;


import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import org.cometd.server.AbstractBayeux;
import org.cometd.server.ChannelImpl;
import org.cometd.server.MessageImpl;
import org.cometd.server.continuation.ContinuationCometdServlet;

import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.MovedContextHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.thread.QueuedThreadPool;


/* ------------------------------------------------------------ */
/** Main class for cometd demo.
 *
 * @author gregw
 *
 */
public class CometdDemo
{
    private static int _testHandshakeFailure;

    /* ------------------------------------------------------------ */
    /**
     * @param args
     */
    public static void main(String[] args) throws Exception
    {
        int port = args.length==0?8080:Integer.parseInt(args[0]);

        String base="..";

        // Manually contruct context to avoid hassles with webapp classloaders for now.
        Server server = new Server();
        QueuedThreadPool qtp = new QueuedThreadPool();
        qtp.setMinThreads(5);
        qtp.setMaxThreads(200);
        server.setThreadPool(qtp);

        SelectChannelConnector connector=new SelectChannelConnector();
        // SocketConnector connector=new SocketConnector();
        connector.setPort(port);
        connector.setMaxIdleTime(120000);
        connector.setLowResourceMaxIdleTime(60000);
        connector.setLowResourcesConnections(20000);
        connector.setAcceptQueueSize(5000);
        server.addConnector(connector);
        SocketConnector bconnector=new SocketConnector();
        bconnector.setPort(port+1);
        server.addConnector(bconnector);


        SslSelectChannelConnector ssl_connector=new SslSelectChannelConnector();
        ssl_connector.setPort(port-80+443);
        ssl_connector.setKeystore(base+"/examples/src/test/resources/keystore");
        ssl_connector.setPassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        ssl_connector.setKeyPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        ssl_connector.setTruststore(base+"/examples/src/test/resources/keystore");
        ssl_connector.setTrustPassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        server.addConnector(ssl_connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        MovedContextHandler moved = new MovedContextHandler(contexts,"/","/cometd");
        moved.setDiscardPathInfo(true);

        ServletContextHandler context = new ServletContextHandler(contexts,"/cometd",ServletContextHandler.SESSIONS);

        // Search for demo webapp
        Resource target = Resource.newResource("../../cometd-demo/target");
        Resource webdir = null;
        for (String item : target.list())
        {
            if (item.startsWith("cometd-demo-"))
            {
                Resource dir = target.addPath(item);
                if (dir.isDirectory())
                    webdir=dir;
            }
        }
        assert webdir!=null;
        context.setBaseResource(new ResourceCollection(new Resource[]
        {
            webdir,
        }));

        // Cometd servlet

        ServletHolder dftServlet = context.addServlet(DefaultServlet.class, "/");
        dftServlet.setInitOrder(1);

        ServletHolder comet = context.addServlet(ContinuationCometdServlet.class, "/cometd/*");
        comet.setInitParameter("filters","/WEB-INF/filters.json");
        comet.setInitParameter("timeout","20000");
        comet.setInitParameter("interval","50");
        comet.setInitParameter("maxInterval","20000");
        comet.setInitParameter("multiFrameInterval","5000");
        comet.setInitParameter("logLevel","0");
        comet.setInitParameter("jsonDebug","true");
        comet.setInitOrder(2);


        ServletHolder demo=context.addServlet(CometdDemoServlet.class, "/demo");
        demo.setInitOrder(3);

        server.start();

        final AbstractBayeux bayeux = ((ContinuationCometdServlet)comet.getServlet()).getBayeux();
        bayeux.setRequestAvailable(true);

        bayeux.setSecurityPolicy(new AbstractBayeux.DefaultPolicy()
        {
            public boolean canHandshake(Message message)
            {
                if (_testHandshakeFailure<0)
                {
                    _testHandshakeFailure++;
                    return false;
                }
                return true;
            }
        });

        // Demo lazy messages
        if (Boolean.getBoolean("LAZY"))
        {
            bayeux.addExtension(new Extension()
            {
                public Message rcv(Client from, Message message)
                {
                    if (message.getChannel().startsWith("/chat/") && message.getData()!=null && message.getData().toString().indexOf("lazy")>=0)
                        ((MessageImpl)message).setLazy(true);
                    return message;
                }
                public Message rcvMeta(Client from, Message message)
                {
                    return message;
                }
                public Message send(Client from, Message message)
                {
                    return message;
                }
                public Message sendMeta(Client from, Message message)
                {
                    return message;
                }
            });
        }

        // Demo lazy messages
        if (Boolean.getBoolean("LAZYCHAT"))
        {
            final ChannelImpl chat_demo = (ChannelImpl)bayeux.getChannel("/chat/demo",true);
            chat_demo.setLazy(true);
            chat_demo.setPersistent(true);

            Timer timer = new Timer();
            timer.schedule(new TimerTask()
            {
                public void run()
                {
                    chat_demo.publish(null,new JSON.Literal("{\"user\":\"TICK\",\"chat\":\""+new Date()+"\"}"),null);
                }
            },2000,2000);
        }

    }
}
