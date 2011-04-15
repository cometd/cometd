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

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.java.annotation.AnnotationCometdServlet;
import org.cometd.server.CometdServlet;
import org.cometd.server.DefaultSecurityPolicy;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
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
        connector.setLowResourcesMaxIdleTime(60000);
        connector.setLowResourcesConnections(20000);
        connector.setAcceptQueueSize(5000);
        server.addConnector(connector);
        SocketConnector bconnector=new SocketConnector();
        bconnector.setPort(port+1);
        server.addConnector(bconnector);


        /*
        SslSelectChannelConnector ssl_connector=new SslSelectChannelConnector();
        ssl_connector.setPort(port-80+443);
        ssl_connector.setKeystore(base+"/examples/src/test/resources/keystore");
        ssl_connector.setPassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        ssl_connector.setKeyPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        ssl_connector.setTruststore(base+"/examples/src/test/resources/keystore");
        ssl_connector.setTrustPassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        server.addConnector(ssl_connector);
        */

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        // MovedContextHandler moved = new MovedContextHandler(contexts,"/","/cometd");
        // moved.setDiscardPathInfo(true);

        ServletContextHandler context = new ServletContextHandler(contexts,"/",ServletContextHandler.SESSIONS);
        context.setBaseResource(
                new ResourceCollection(new Resource[]
                {
                        Resource.newResource("../../cometd-demo/src/main/webapp/"),

                        Resource.newResource("../../cometd-javascript/common/src/main/webapp/"),
                        Resource.newResource("../../cometd-javascript/jquery/src/main/webapp/"),
                        Resource.newResource("../../cometd-javascript/examples-jquery/src/main/webapp/"),

                        Resource.newResource("../../cometd-javascript/dojo/src/main/webapp/"),
                        Resource.newResource("../../cometd-javascript/examples-dojo/src/main/webapp/"),
                        Resource.newResource("../../cometd-javascript/dojo/target/war/work/org.dojotoolkit/dojo-war/")
                }));


        ServletHolder dftServlet = context.addServlet(DefaultServlet.class, "/");
        dftServlet.setInitOrder(1);

        // Cometd servlet
        CometdServlet cometdServlet = new AnnotationCometdServlet();
        ServletHolder comet = new ServletHolder(cometdServlet);
        context.addServlet(comet, "/cometd/*");
        comet.setInitParameter("timeout","20000");
        comet.setInitParameter("interval","100");
        comet.setInitParameter("maxInterval","10000");
        comet.setInitParameter("multiFrameInterval","5000");
        comet.setInitParameter("logLevel","1");
        comet.setInitParameter("services","org.cometd.examples.ChatService");
        comet.setInitParameter("transports","org.cometd.server.websocket.WebSocketTransport");
        comet.setInitOrder(2);

        ServletHolder demo=context.addServlet(CometdDemoServlet.class, "/demo");
        demo.setInitOrder(3);

        server.start();

        BayeuxServer bayeux = cometdServlet.getBayeux();
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy());

        // Demo lazy messages
        if (Boolean.getBoolean("LAZY"))
        {
            bayeux.addExtension(new BayeuxServer.Extension()
            {
                public boolean sendMeta(ServerSession to, Mutable message)
                {
                    return true;
                }

                public boolean send(ServerSession from, ServerSession to, Mutable message)
                {
                    return true;
                }

                public boolean rcvMeta(ServerSession from, Mutable message)
                {
                    return true;
                }

                public boolean rcv(ServerSession from, Mutable message)
                {
                    if (message.getChannel().startsWith("/chat/") && message.getData()!=null && message.getData().toString().indexOf("lazy")>=0)
                        (message).setLazy(true);
                    return true;
                }
            });
        }

        // Demo lazy messages
        if (Boolean.getBoolean("LAZYCHAT"))
        {
            String channelName = "/chat/demo";
            bayeux.createIfAbsent(channelName);
            final ServerChannel chat_demo = bayeux.getChannel(channelName);
            chat_demo.setLazy(true);
            chat_demo.setPersistent(true);
        }

    }
}
