package org.cometd.client;

import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.cometd.server.AbstractBayeux;
import org.cometd.server.MessageImpl;
import org.cometd.server.continuation.ContinuationCometdServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class BayeuxClientTest extends TestCase
{
    Server _server;
    ContinuationCometdServlet _cometd;
    
    protected void setUp() throws Exception
    {
        super.setUp();
        
        // Manually contruct context to avoid hassles with webapp classloaders for now.
        _server = new Server();
        QueuedThreadPool qtp = new QueuedThreadPool();
        qtp.setMinThreads(5);
        qtp.setMaxThreads(20);
        _server.setThreadPool(qtp);
        
        SelectChannelConnector connector=new SelectChannelConnector();
        // SocketConnector connector=new SocketConnector();
        connector.setPort(0);
        _server.addConnector(connector);
        
        ServletContextHandler context = new ServletContextHandler(_server,"/");
        context.setBaseResource(Resource.newResource("./src/test"));
        
        // Cometd servlet
        _cometd=new ContinuationCometdServlet();
        ServletHolder cometd_holder = new ServletHolder(_cometd);
        cometd_holder.setInitParameter("timeout","10000");
        cometd_holder.setInitParameter("interval","100");
        cometd_holder.setInitParameter("maxInterval","5000");
        cometd_holder.setInitParameter("multiFrameInterval","2000");
        cometd_holder.setInitParameter("logLevel","1");
    
        context.addServlet(cometd_holder, "/cometd/*");
        context.addServlet("org.mortbay.jetty.servlet.DefaultServlet", "/"); 
        
    }

    public void testClient() throws Exception
    {
        
        _server.start();
        AbstractBayeux bayeux = _cometd.getBayeux();
        
        HttpClient httpClient = new HttpClient();
        httpClient.start();

        final Exchanger<Object> exchanger = new Exchanger<Object>();
        
        BayeuxClient client = new BayeuxClient(httpClient,"http://localhost:"+_server.getConnectors()[0].getLocalPort()+"/cometd")
        {
            volatile boolean connected;
            protected void metaConnect(boolean success, Message message)
            {
                super.metaConnect(success,message);
                if (!connected)
                {
                    connected=true;
                    try
                    {
                        ((MessageImpl)message).incRef();
                        exchanger.exchange(message,1,TimeUnit.SECONDS);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }

            protected void metaHandshake(boolean success, boolean reestablish, Message message)
            {
                connected=false;
                super.metaHandshake(success,reestablish,message);
                try
                {
                    ((MessageImpl)message).incRef();
                    exchanger.exchange(message,1,TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            
        };
        
        client.addListener(new MessageListener(){
            public void deliver(Client fromClient, Client toClient, Message message)
            {
                if (message.getData()!=null || Bayeux.META_SUBSCRIBE.equals(message.getChannel()) || Bayeux.META_DISCONNECT.equals(message.getChannel()))
                {
                    try
                    {
                        ((MessageImpl)message).incRef();
                        exchanger.exchange(message,1,TimeUnit.SECONDS);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        });
        
        client.addLifeCycleListener(new LifeCycle.Listener(){

            public void lifeCycleFailure(LifeCycle event, Throwable cause)
            {
            }

            public void lifeCycleStarted(LifeCycle event)
            {
            }

            public void lifeCycleStarting(LifeCycle event)
            {
            }

            public void lifeCycleStopped(LifeCycle event)
            {
                try
                {
                    exchanger.exchange(event,1,TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }

            public void lifeCycleStopping(LifeCycle event)
            {
            }
        }
        );
        
        
        client.start();
        
        MessageImpl message = (MessageImpl)exchanger.exchange(null,1,TimeUnit.SECONDS);
        assertEquals(Bayeux.META_HANDSHAKE,message.getChannel());
        assertTrue(message.isSuccessful());
        String id = client.getId();
        assertTrue(id!=null);
        message.decRef();
        
        message = (MessageImpl)exchanger.exchange(null,1,TimeUnit.SECONDS);
        assertEquals(Bayeux.META_CONNECT,message.getChannel());
        assertTrue(message.isSuccessful());
        message.decRef();
        
        client.subscribe("/a/channel");
        message = (MessageImpl)exchanger.exchange(null,1,TimeUnit.SECONDS);
        assertEquals(Bayeux.META_SUBSCRIBE,message.getChannel());
        assertTrue(message.isSuccessful());
        message.decRef();
        
        client.publish("/a/channel","data","id");
        message = (MessageImpl)exchanger.exchange(null,1,TimeUnit.SECONDS);
        assertEquals("data",message.getData());
        message.decRef();
        
        client.disconnect();
        message = (MessageImpl)exchanger.exchange(null,1,TimeUnit.SECONDS);
        assertEquals(Bayeux.META_DISCONNECT,message.getChannel());
        assertTrue(message.isSuccessful());
        message.decRef();

        Object o = exchanger.exchange(null,1,TimeUnit.SECONDS);
        
        assertTrue(client.isStopped());
    }

}
