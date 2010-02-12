package org.cometd.client;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.SessionChannel;
import org.cometd.bayeux.client.SessionChannel.MetaChannelListener;
import org.cometd.bayeux.client.SessionChannel.SubscriptionListener;
import org.cometd.client.BayeuxClient.State;
import org.cometd.common.HashMapMessage;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.log.Log;
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
        ServletHolder cometd_holder = new ServletHolder(CometdServlet.class);
        cometd_holder.setInitParameter("timeout","10000");
        cometd_holder.setInitParameter("interval","100");
        cometd_holder.setInitParameter("maxInterval","100000");
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

        BayeuxClient client = new BayeuxClient(_httpClient,"http://localhost:"+_port+"/cometd");
        
        final AtomicBoolean connected = new AtomicBoolean();
        
        client.getChannel(Channel.META_CONNECT).addListener(new MetaChannelListener()
        {
            @Override
            public void onMetaMessage(SessionChannel channel, Message message, boolean successful, String error)
            {
                connected.set(successful);
            }
        });
        
        client.getChannel(Channel.META_HANDSHAKE).addListener(new MetaChannelListener()
        {
            @Override
            public void onMetaMessage(SessionChannel channel, Message message, boolean successful, String error)
            {
                connected.set(false);
            }
        });
        
        client.getChannel("/meta/*").addListener(new MetaChannelListener()
        {
            @Override
            public void onMetaMessage(SessionChannel channel, Message message, boolean successful, String error)
            {
                try
                {
                    System.out.println("<<"+message+" @ "+channel);
                    exchanger.exchange(message,5,TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });
        
        client.handshake();

        Message message = (Message)exchanger.exchange(null,1,TimeUnit.SECONDS);
        assertEquals(Channel.META_HANDSHAKE,message.getChannel());
        assertTrue(message.isSuccessful());
        String id = client.getId();
        assertTrue(id!=null);

        message = (Message)exchanger.exchange(null,1,TimeUnit.SECONDS);
        assertEquals(Channel.META_CONNECT,message.getChannel());
        assertTrue(message.isSuccessful());

        client.getChannel("/a/channel").subscribe(new SubscriptionListener()
        {
            @Override
            public void onMessage(SessionChannel channel, Message message)
            {
                try
                {
                    System.out.println("a<"+message+" @ "+channel);
                    exchanger.exchange(message,5,TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });
        
        message = (Message)exchanger.exchange(null,1,TimeUnit.SECONDS);
        assertEquals(Channel.META_SUBSCRIBE,message.getChannel());
        assertTrue(message.isSuccessful());

        client.getChannel("/a/channel").publish("data");
        message = (Message)exchanger.exchange(null,1,TimeUnit.SECONDS);
        assertEquals("data",message.getData());

        client.disconnect();
        boolean connect_first=false;
        message = (Message)exchanger.exchange(null,1,TimeUnit.SECONDS);
        if (Channel.META_CONNECT.equals(message.getChannel()))
        {
            connect_first=true;
            message = (Message)exchanger.exchange(null,1,TimeUnit.SECONDS);
        }
        assertEquals(Channel.META_DISCONNECT,message.getChannel());
        assertTrue(message.isSuccessful());
        
        if (!connect_first)
        {
            message = (Message)exchanger.exchange(null,1,TimeUnit.SECONDS);
            assertEquals(Channel.META_CONNECT,message.getChannel());
            assertTrue(message.isSuccessful());
        }
    }
    
    public void testRetry() throws Exception
    {
        final BlockingArrayQueue<Message> queue = new BlockingArrayQueue<Message>(100,100);
        final Message.Mutable problem = new HashMapMessage();
        problem.setSuccessful(false);

        final AtomicBoolean connected = new AtomicBoolean(false);
        
        BayeuxClient client = new BayeuxClient(_httpClient,"http://localhost:"+_port+"/cometd")
        {
            @Override
            public void onProtocolError(String info)
            {
                super.onProtocolError(info);
                queue.add(problem);
                _filter._code=0;
            }

            @Override
            public void onConnectException(Throwable x)
            {
                if (x instanceof RuntimeException || x instanceof Error)
                    Log.warn("onConnectException: ",x);
                else
                    Log.warn("onConnectException: "+x.toString());
                queue.add(problem);
            }

            @Override
            public void onException(Throwable x)
            {
                if (x instanceof RuntimeException || x instanceof Error)
                    Log.warn("onException: ",x);
                else
                    Log.warn("onException: "+x.toString());
                queue.add(problem);
            }

            @Override
            public void onExpire()
            {
                Log.warn("onExpire: ");
                queue.add(problem);
            }
            
        };
                
        client.getChannel(Channel.META_CONNECT).addListener(new SessionChannel.MetaChannelListener()
        {    
            @Override
            public void onMetaMessage(SessionChannel channel, Message message, boolean successful, String error)
            {
                connected.set(successful);
                try
                {
                    queue.offer(message);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });

        client.getChannel(Channel.META_HANDSHAKE).addListener(new SessionChannel.MetaChannelListener()
        {    
            @Override
            public void onMetaMessage(SessionChannel channel, Message message, boolean successful, String error)
            {
                connected.set(false);
                try
                {
                    queue.offer(message);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });

        client.getChannel("/**").addListener(new SessionChannel.MessageListener()
        {
            @Override
            public void onMessage(ClientSession session, Message message)
            {
                if (message.getData()!=null || Channel.META_SUBSCRIBE.equals(message.getChannel()) || Channel.META_DISCONNECT.equals(message.getChannel()))
                {
                    try
                    {
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
        client.handshake();

        Message message = queue.poll(1,TimeUnit.SECONDS);
        assertFalse(message.isSuccessful());
        
        message = queue.poll(2,TimeUnit.SECONDS);
        assertEquals(0,_filter._code);
        assertTrue(message.isSuccessful());
        String id = client.getId();
        assertTrue(id!=null);

        message = queue.poll(1,TimeUnit.SECONDS);
        assertEquals(Channel.META_CONNECT,message.getChannel());
        assertTrue(message.isSuccessful());

        _server.stop();

        Thread.sleep(500);
        
        

        message=queue.poll(1,TimeUnit.SECONDS);
        assertFalse(message.isSuccessful());

        while ((message=queue.poll(1,TimeUnit.SECONDS))!=null)
        {
            assertFalse(message.isSuccessful());
        }

        _connector.setPort(_port);
        _server.start();

        message=queue.poll(5,TimeUnit.SECONDS);
        System.err.println(message);

        assertFalse(message.isSuccessful());
        assertEquals("402::Unknown client",message.get("error"));

        client.disconnect();
        assertTrue(client.waitFor(State.DISCONNECTED,1000L));
    }

    public void testCookies() throws Exception
    {
        BayeuxClient client = new BayeuxClient(_httpClient,"http://localhost:"+_port+"/cometd");
        client.handshake();

        
        client.setCookie("foo","bar",1);
        assertNotNull(client.getCookie("foo"));

        // Allow cookie to expire
        Thread.sleep(1100);

        assertNull(client.getCookie("foo"));

        client.setCookie("foo","bar");
        assertNotNull(client.getCookie("foo"));

        Thread.sleep(1100);
        
        assertNotNull(client.getCookie("foo"));
        
        client.disconnect();
        assertTrue(client.waitFor(State.DISCONNECTED,1000L));
    }

    public void testPerf() throws Exception
    {
        Runtime.getRuntime().addShutdownHook(new DumpThread());

        final int rooms=_stress?100:10;
        final int publish=_stress?4000:100;
        final int batch=_stress?10:2;
        final int pause=_stress?50:10;
        BayeuxClient[] clients= new BayeuxClient[_stress?500:2*rooms];

        final AtomicInteger connections=new AtomicInteger();
        final AtomicInteger received=new AtomicInteger();

        for (int i=0;i<clients.length;i++)
        {
            final int cid=i;
            final AtomicBoolean connected=new AtomicBoolean();
            final BayeuxClient client=new BayeuxClient(_httpClient,"http://localhost:"+_port+"/cometd");
            final String room="/channel/"+(i%rooms);
            clients[i] = client;

            client.getChannel(Channel.META_HANDSHAKE).addListener(new SessionChannel.MetaChannelListener()
            {    
                @Override
                public void onMetaMessage(SessionChannel channel, Message message, boolean successful, String error)
                {
                    if (connected.getAndSet(false))
                        connections.decrementAndGet();
                    
                    if (successful)
                    {
                        client.getChannel(room).subscribe(new SubscriptionListener()
                        {
                            @Override
                            public void onMessage(SessionChannel channel, Message message)
                            {
                                received.incrementAndGet();
                            }
                        });
                    }
                }
            });
            
            client.getChannel(Channel.META_CONNECT).addListener(new SessionChannel.MetaChannelListener()
            {    
                @Override
                public void onMetaMessage(SessionChannel channel, Message message, boolean successful, String error)
                {
                    if (!connected.getAndSet(successful))
                    {
                        connections.incrementAndGet();
                    }
                }
            });
                    
            clients[i].handshake();
        }

        long start=System.currentTimeMillis();
        Thread.sleep(100);
        while(connections.get()<clients.length && (System.currentTimeMillis()-start)<10000)
        {
            Thread.sleep(1000);
            System.err.println("connected "+connections.get()+"/"+clients.length);
        }

        assertEquals(clients.length,connections.get());

        long start0=System.currentTimeMillis();
        for (int i=0;i<publish;i++)
        {
            final int sender=_random.nextInt(clients.length);
            final String channel="/channel/"+_random.nextInt(rooms);

            String data="data from "+sender+" to "+channel;
            // System.err.println(data);
            clients[sender].getChannel(channel).publish(data,""+i);

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

        for (BayeuxClient client : clients)
            assertTrue(client.waitFor(State.DISCONNECTED,1000L));
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
