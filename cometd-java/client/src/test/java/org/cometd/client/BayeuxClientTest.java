package org.cometd.client;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;
import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.cometd.server.MessageImpl;
import org.cometd.server.continuation.ContinuationCometdServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;

public class BayeuxClientTest extends TestCase
{
    private boolean _stress=Boolean.getBoolean("STRESS");
    private Server _server;
    private SelectChannelConnector _connector;
    private Random _random = new Random();
    private HttpClient _httpClient;
    private TestFilter _filter;
    private int _port;

    protected void setUp() throws Exception
    {
        // Manually construct context to avoid hassles with webapp classloaders for now.
        _server = new Server();

        _connector=new SelectChannelConnector();
        // SocketConnector connector=new SocketConnector();
        _connector.setPort(0);
        _connector.setMaxIdleTime(30000);
        _server.addConnector(_connector);

        ServletContextHandler context = new ServletContextHandler(_server,"/");
        context.setBaseResource(Resource.newResource("./src/test"));

        // Test Filter
        _filter = new TestFilter();
        context.addFilter(new FilterHolder(_filter),"/*",0);

        // Cometd servlet
        ContinuationCometdServlet cometd = new ContinuationCometdServlet();
        ServletHolder cometd_holder = new ServletHolder(cometd);
        cometd_holder.setInitParameter("timeout","10000");
        cometd_holder.setInitParameter("interval","100");
        cometd_holder.setInitParameter("maxInterval","10000");
        cometd_holder.setInitParameter("multiFrameInterval","2000");
        cometd_holder.setInitParameter("logLevel","0");

        context.addServlet(cometd_holder, "/cometd/*");
        context.addServlet(DefaultServlet.class, "/");

        _server.start();

        _httpClient = new HttpClient();
        _httpClient.setMaxConnectionsPerAddress(20000);
        _httpClient.setIdleTimeout(15000);
        _httpClient.start();

        _port=_connector.getLocalPort();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see junit.framework.TestCase#tearDown()
     */
    @Override
    protected void tearDown() throws Exception
    {
        if (_httpClient!=null)
            _httpClient.stop();
        _httpClient=null;

        if (_server!=null)
            _server.stop();
        _server=null;
    }

    /* ------------------------------------------------------------ */
    public void testClient() throws Exception
    {
        final Exchanger<Object> exchanger = new Exchanger<Object>();

        BayeuxClient client = new BayeuxClient(_httpClient,"http://localhost:"+_port+"/cometd")
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

        exchanger.exchange(null,1,TimeUnit.SECONDS);

        assertTrue(client.isStopped());
    }

    /* ------------------------------------------------------------ */
    public void testRetry() throws Exception
    {
        final BlockingArrayQueue<Object> queue = new BlockingArrayQueue<Object>(100,100);

        BayeuxClient client = new BayeuxClient(_httpClient,"http://localhost:"+_port+"/cometd")
        {
            volatile boolean connected;
            protected void metaConnect(boolean success, Message message)
            {
                super.metaConnect(success,message);
                connected|=success;
                try
                {
                    ((MessageImpl)message).incRef();
                    queue.offer(message);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }

            protected void metaHandshake(boolean success, boolean reestablish, Message message)
            {
                _filter._code=0;
                connected=false;
                super.metaHandshake(success,reestablish,message);
                try
                {
                    ((MessageImpl)message).incRef();
                    queue.offer(message);
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
                        queue.offer(message);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        });

        _filter._code=503;
        client.start();

        MessageImpl message = (MessageImpl)queue.poll(1,TimeUnit.SECONDS);
        assertFalse(message.isSuccessful());
        message.decRef();

        message = (MessageImpl)queue.poll(1,TimeUnit.SECONDS);
        assertTrue(message.isSuccessful());
        String id = client.getId();
        assertTrue(id!=null);
        message.decRef();

        message = (MessageImpl)queue.poll(1,TimeUnit.SECONDS);
        assertEquals(Bayeux.META_CONNECT,message.getChannel());
        assertTrue(message.isSuccessful());
        message.decRef();

        _server.stop();

        Thread.sleep(500);

        message=(MessageImpl)queue.poll(1,TimeUnit.SECONDS);
        assertFalse(message.isSuccessful());

        while ((message=(MessageImpl)queue.poll(1,TimeUnit.SECONDS))!=null)
        {
            assertFalse(message.isSuccessful());
        }

        _connector.setPort(_port);
        _server.start();

        message=(MessageImpl)queue.poll(2,TimeUnit.SECONDS);
        System.err.println(message);

        assertFalse(message.isSuccessful());
        assertEquals("402::Unknown client",message.get("error"));

        client.disconnect();
        // Wait for the disconnect to complete
        Thread.sleep(500);
    }

    public void testCookies() throws Exception
    {
        BayeuxClient client = new BayeuxClient(_httpClient,"http://localhost:"+_port+"/cometd");

        String cookieName = "foo";
        Cookie cookie = new Cookie(cookieName, "bar");
        cookie.setMaxAge(1);

        client.setCookie(cookie);
        assertNotNull(client.getCookie(cookieName));

        // Allow cookie to expire
        Thread.sleep(1500);

        assertNull(client.getCookie(cookieName));

        cookie.setMaxAge(-1);
        client.setCookie(cookie);
        assertNotNull(client.getCookie(cookieName));
    }

    /* ------------------------------------------------------------ */
    public void testPerf() throws Exception
    {
        Runtime.getRuntime().addShutdownHook(new DumpThread());

        final int rooms=_stress?100:50;
        final int publish=_stress?4000:2000;
        final int batch=_stress?10:10;
        final int pause=_stress?50:100;
        BayeuxClient[] clients= new BayeuxClient[_stress?500:2*rooms];

        final AtomicInteger connected=new AtomicInteger();
        final AtomicInteger received=new AtomicInteger();

        for (int i=0;i<clients.length;i++)
        {
            clients[i] = new BayeuxClient(_httpClient,"http://localhost:"+_port+"/cometd")
            {
                volatile boolean _connected;
                protected void metaConnect(boolean success, Message message)
                {
                    super.metaConnect(success,message);
                    if (!_connected)
                    {
                        _connected=true;
                        connected.incrementAndGet();
                    }
                }

                protected void metaHandshake(boolean success, boolean reestablish, Message message)
                {
                    if (_connected)
                        connected.decrementAndGet();
                    _connected=false;
                    super.metaHandshake(success,reestablish,message);
                }
            };

            clients[i].addListener(new MessageListener(){
                public void deliver(Client fromClient, Client toClient, Message message)
                {
                    // System.err.println(message);
                    if (message.getData()!=null)
                    {
                        received.incrementAndGet();
                    }
                }
            });

            clients[i].start();
            clients[i].subscribe("/channel/"+(i%rooms));
        }

        long start=System.currentTimeMillis();
        while(connected.get()<clients.length && (System.currentTimeMillis()-start)<30000)
        {
            Thread.sleep(1000);
            System.err.println("connected "+connected.get()+"/"+clients.length);
        }

        assertEquals(clients.length,connected.get());

        long start0=System.currentTimeMillis();
        for (int i=0;i<publish;i++)
        {
            final int sender=_random.nextInt(clients.length);
            final String channel="/channel/"+_random.nextInt(rooms);

            String data="data from "+sender+" to "+channel;
            // System.err.println(data);
            clients[sender].publish(channel,data,""+i);

            if (i%batch==(batch-1))
            {
                System.err.print('.');
                Thread.sleep(pause);
            }
            if (i%1000==999)
                System.err.println();
        }
        System.err.println();

        int expected=clients.length*publish/rooms;

        start=System.currentTimeMillis();
        while(received.get()<expected && (System.currentTimeMillis()-start)<10000)
        {
            Thread.sleep(1000);
            System.err.println("received "+received.get()+"/"+expected);
        }
        System.err.println((received.get()*1000)/(System.currentTimeMillis()-start0)+" m/s");

        assertEquals(expected,received.get());

        for (BayeuxClient client : clients)
            client.disconnect();

        Thread.sleep(clients.length*20);

        for (BayeuxClient client : clients)
            client.stop();
    }

    private class DumpThread extends Thread
    {
        public void run()
        {
            try
            {
                if (_server!=null) _server.dump();
                if (_httpClient!=null) _httpClient.dump();
            }
            catch (Exception x)
            {
                x.printStackTrace();
            }
        }
    }

    private static class TestFilter implements Filter
    {
        volatile int _code=0;

        public void destroy()
        {
        }

        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            if (_code!=0)
                ((HttpServletResponse)response).sendError(_code);
            else
                chain.doFilter(request,response);
        }

        public void init(FilterConfig filterConfig) throws ServletException
        {
        }
    }
}
