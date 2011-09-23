/*
 * Copyright (c) 2010 the original author or authors.
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

package org.cometd.examples;

import javax.servlet.http.HttpServletRequest;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.examples.CometdDemoServlet.EchoRPC;
import org.cometd.examples.CometdDemoServlet.Monitor;
import org.cometd.java.annotation.AnnotationCometdServlet;
import org.cometd.java.annotation.ServerAnnotationProcessor;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometdServlet;
import org.cometd.server.DefaultSecurityPolicy;
import org.cometd.server.MultiTenantCometdServlet;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.server.ext.TimesyncExtension;
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
/**
 * Main class for cometd demo.
 */
public class MultiTentantDemo
{
    /* ------------------------------------------------------------ */
    /**
     * @param args
     */
    public static void main(String[] args) throws Exception
    {
        int port = args.length==0?8080:Integer.parseInt(args[0]);

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
                        Resource.newResource("../../cometd-javascript/common-test/target/scripts/"),
                        Resource.newResource("../../cometd-javascript/jquery/src/main/webapp/"),
                        Resource.newResource("../../cometd-javascript/examples-jquery/src/main/webapp/"),

                        Resource.newResource("../../cometd-javascript/dojo/src/main/webapp/"),
                        Resource.newResource("../../cometd-javascript/examples-dojo/src/main/webapp/"),
                        Resource.newResource("../../cometd-javascript/dojo/target/war/work/org.dojotoolkit/dojo-war/")
                }));


        ServletHolder dftServlet = context.addServlet(DefaultServlet.class, "/");
        dftServlet.setInitOrder(1);

        // Cometd servlet
        CometdServlet cometdServlet = new MultiTenantCometdServlet()
        {

            @Override
            protected void customise(BayeuxServerImpl bayeux)
            {
                bayeux.setSecurityPolicy(new DefaultSecurityPolicy());
                bayeux.createIfAbsent("/**",new ServerChannel.Initializer()
                {
                    public void configureChannel(ConfigurableServerChannel channel)
                    {
                        channel.addAuthorizer(GrantAuthorizer.GRANT_NONE);
                    }
                });
                bayeux.getChannel(ServerChannel.META_HANDSHAKE).addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);
                
                
                bayeux.addExtension(new TimesyncExtension());
                bayeux.addExtension(new AcknowledgedMessagesExtension());
                
                ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
                processor.process(new ChatService());
                processor.process(new EchoRPC());
                processor.process(new Monitor());
                
                // TODO find a way to deprocess services on stop/idle tenant
            }

            @Override
            protected String getTenantId(HttpServletRequest request)
            {
                // Use local address as simple tenant ID
                return request.getLocalAddr().toString().replace('/','_');
            }
            
        };
        
        ServletHolder comet = new ServletHolder(cometdServlet);
        context.addServlet(comet, "/cometd/*");
        comet.setInitParameter("timeout","20000");
        comet.setInitParameter("interval","100");
        comet.setInitParameter("maxInterval","10000");
        comet.setInitParameter("multiFrameInterval","5000");
        comet.setInitParameter("logLevel","1");
        comet.setInitParameter("transports","org.cometd.websocket.server.WebSocketTransport");
        comet.setInitOrder(2);


        server.start();
        

    }
}
