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

package org.cometd.demo;


import java.util.Date;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import org.mortbay.cometd.AbstractBayeux;
import org.mortbay.cometd.ChannelImpl;
import org.mortbay.cometd.ClientImpl;
import org.mortbay.cometd.MessageImpl;
import org.mortbay.cometd.continuation.ContinuationCometdServlet;
import org.mortbay.cometd.ext.TimesyncExtension;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.bio.SocketConnector;
import org.mortbay.jetty.handler.ContextHandlerCollection;
import org.mortbay.jetty.handler.MovedContextHandler;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.security.SslSelectChannelConnector;
import org.mortbay.jetty.security.SslSocketConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.DefaultServlet;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.resource.Resource;
import org.mortbay.resource.ResourceCollection;
import org.mortbay.thread.QueuedThreadPool;
import org.mortbay.util.ajax.JSON;

import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;


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
     
        String base="../../..";
        
        // Manually contruct context to avoid hassles with webapp classloaders for now.
        Server server = new Server();
        QueuedThreadPool qtp = new QueuedThreadPool();
        qtp.setMinThreads(5);
        qtp.setMaxThreads(200);
        server.setThreadPool(qtp);
        
        SelectChannelConnector connector=new SelectChannelConnector();
        // SocketConnector connector=new SocketConnector();
        connector.setPort(port);
        server.addConnector(connector);
        SocketConnector bconnector=new SocketConnector();
        bconnector.setPort(port+1);
        server.addConnector(bconnector);
        
        
        /*
        SslSelectChannelConnector ssl_connector=new SslSelectChannelConnector();
        ssl_connector.setPort(port-80+443);
        ssl_connector.setKeystore(base+"/etc/keystore");
        ssl_connector.setPassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        ssl_connector.setKeyPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        ssl_connector.setTruststore(base+"/etc/keystore");
        ssl_connector.setTrustPassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        server.addConnector(ssl_connector);  
        */

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        
        MovedContextHandler moved = new MovedContextHandler(contexts,"/","/cometd");
        moved.setDiscardPathInfo(true);
        
        Context context = new Context(contexts,"/cometd",Context.NO_SECURITY|Context.SESSIONS);
        
        context.setBaseResource(new ResourceCollection(new Resource[]
        {
            Resource.newResource("./src/main/webapp/"),
            Resource.newResource("./target/cometd-jetty-demo-1.0-SNAPSHOT/"),
        }));
        
        
        // Cometd servlet

        ServletHolder dftServlet = context.addServlet(DefaultServlet.class, "/");
        dftServlet.setInitOrder(1);

        ServletHolder comet = context.addServlet(ContinuationCometdServlet.class, "/cometd/*");
        comet.setInitParameter("filters","/WEB-INF/filters.json");
        comet.setInitParameter("timeout","20000");
        comet.setInitParameter("interval","100");
        comet.setInitParameter("maxInterval","10000");
        comet.setInitParameter("multiFrameInterval","5000");
        comet.setInitParameter("logLevel","1");
        comet.setInitOrder(2);
        
        
        ServletHolder demo=context.addServlet(CometdDemoServlet.class, "/demo");
        demo.setInitOrder(3);
        
        server.start();
        
        final AbstractBayeux bayeux = ((ContinuationCometdServlet)comet.getServlet()).getBayeux();
        
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
