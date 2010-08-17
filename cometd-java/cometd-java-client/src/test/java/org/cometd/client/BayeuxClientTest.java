package org.cometd.client;

import java.io.IOException;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient.State;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.ChannelId;
import org.cometd.common.HashMapMessage;
import org.cometd.server.BayeuxServerImpl;
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
    private BayeuxServerImpl _bayeux;

    @Override
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
        cometd_holder.setInitOrder(1);

        context.addServlet(cometd_holder, "/cometd/*");
        context.addServlet(DefaultServlet.class, "/");

        _server.start();

        _httpClient = new HttpClient();
        _httpClient.setMaxConnectionsPerAddress(20000);
        _httpClient.setIdleTimeout(15000);
        _httpClient.start();

        _port=_connector.getLocalPort();

        _bayeux=(BayeuxServerImpl)context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        if (_bayeux==null)
            throw new IllegalStateException("No Bayeux");
    }

    @Override
    protected void tearDown() throws Exception
    {
        if (_httpClient!=null)
        {
            _httpClient.stop();
            _httpClient=null;
        }

        if (_server!=null)
        {
            _server.stop();
            _server.join();
            _server=null;
        }
    }

    public void testClient() throws Exception
    {
        final BlockingArrayQueue<Object> results = new BlockingArrayQueue<Object>();

        BayeuxClient client = new BayeuxClient("http://localhost:"+_port+"/cometd", LongPollingTransport.create(null, _httpClient));

        final AtomicBoolean connected = new AtomicBoolean();

        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(message.isSuccessful());
            }
        });

        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(false);
            }
        });

        client.getChannel("/meta/*").addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                System.out.println("<<"+message+" @ "+channel);
                results.offer(message);
            }
        });

        client.handshake();

        Message message = (Message)results.poll(1,TimeUnit.SECONDS);
        assertEquals(Channel.META_HANDSHAKE,message.getChannel());
        assertTrue(message.isSuccessful());
        String id = client.getId();
        assertTrue(id!=null);

        message = (Message)results.poll(1,TimeUnit.SECONDS);
        assertEquals(Channel.META_CONNECT,message.getChannel());
        assertTrue(message.isSuccessful());

        ClientSessionChannel.MessageListener subscriber = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                System.out.println("a<" + message + " @ " + channel);
                results.offer(message);
            }
        };
        ClientSessionChannel aChannel = client.getChannel("/a/channel");
        aChannel.subscribe(subscriber);

        message = (Message)results.poll(1,TimeUnit.SECONDS);
        assertEquals(Channel.META_SUBSCRIBE,message.getChannel());
        assertTrue(message.isSuccessful());

        String data = "data";
        aChannel.publish(data);
        message = (Message)results.poll(1,TimeUnit.SECONDS);
        assertEquals(data, message.getData());

        aChannel.unsubscribe(subscriber);
        message = (Message)results.poll(1,TimeUnit.SECONDS);
        assertEquals(Channel.META_UNSUBSCRIBE,message.getChannel());
        assertTrue(message.isSuccessful());

        client.disconnect();
        assertTrue(client.waitFor(1000,BayeuxClient.State.DISCONNECTED));
    }

    public void testAsync() throws Exception
    {
        BayeuxClient client = new BayeuxClient("http://localhost:"+_port+"/cometd", LongPollingTransport.create(null, _httpClient));

        final AtomicBoolean connected = new AtomicBoolean();

        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(message.isSuccessful());
            }
        });

        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(false);
            }
        });

        final BlockingArrayQueue<String> messages = new BlockingArrayQueue<String>();

        client.handshake();

        client.getChannel("/foo/bar").subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                System.err.println("message "+message);
                messages.add(channel.getId());
                messages.add(message.getData().toString());
            }
        });
        client.getChannel("/foo/bar").publish("Hello");

        assertTrue(client.waitFor(1000,BayeuxClient.State.CONNECTED));

        assertEquals("/foo/bar",messages.poll(1,TimeUnit.SECONDS));
        assertEquals("Hello",messages.poll(1,TimeUnit.SECONDS));

        client.disconnect();
        assertTrue(client.waitFor(1000,BayeuxClient.State.DISCONNECTED));
    }

    public void testRetry() throws Exception
    {
        final BlockingArrayQueue<Message> queue = new BlockingArrayQueue<Message>(100,100);
        final Message.Mutable problem = new HashMapMessage();
        problem.setSuccessful(false);

        final AtomicBoolean connected = new AtomicBoolean(false);

        BayeuxClient client = new BayeuxClient("http://localhost:"+_port+"/cometd", LongPollingTransport.create(null, _httpClient))
        {
            @Override
            public void onProtocolError(String info)
            {
                super.onProtocolError(info);
                problem.put("error","P protocol error "+info);
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
                problem.put("error","P connect exception "+x);
                queue.add(problem);
            }

            @Override
            public void onException(Throwable x)
            {
                if (x instanceof RuntimeException || x instanceof Error)
                    Log.warn("onException: ",x);
                else
                    Log.warn("onException: "+x.toString());
                problem.put("error","P exception "+x);
                queue.add(problem);
            }

            @Override
            public void onExpire()
            {
                Log.warn("onExpire: ");
                problem.put("error","P expired");
                queue.add(problem);
            }

        };

        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(message.isSuccessful());
                queue.offer(message);
            }
        });

        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(false);
                queue.offer(message);
            }
        });

        client.getChannel("/**").addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel session, Message message)
            {
                if (message.getData()!=null || Channel.META_SUBSCRIBE.equals(message.getChannel()) || Channel.META_DISCONNECT.equals(message.getChannel()))
                {
                    queue.offer(message);
                }
            }
        });

        _filter._code=503;

        long backoffIncrement = 1000L;
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, backoffIncrement);
        client.handshake();

        Message message = queue.poll(1,TimeUnit.SECONDS);
        assertSame(problem, message);
        assertFalse(message.isSuccessful());
        Object error = message.get("error");
        assertTrue(error instanceof String);
        assertTrue(((String)error).contains("protocol error"));
        assertEquals(0,_filter._code);

        message = queue.poll(2*backoffIncrement,TimeUnit.MILLISECONDS);
        assertTrue(message.isSuccessful());
        assertNotNull(client.getId());

        message = queue.poll(1,TimeUnit.SECONDS);
        assertEquals(Channel.META_CONNECT,message.getChannel());
        assertTrue(message.isSuccessful());

        // Abruptly stop the server, client must reconnect
        _server.stop();
        _server.join();
        Thread.sleep(500);

        while ((message=queue.poll(1,TimeUnit.SECONDS))!=null)
            assertFalse(message.isSuccessful());

        _connector.setPort(_port);
        _server.start();

        message=queue.poll(5,TimeUnit.SECONDS);
        error = message.get("error");
        while (error != null && error.toString().startsWith("P "))
            message=queue.poll(10,TimeUnit.SECONDS);

        assertFalse(message.isSuccessful());
        assertEquals("402::Unknown client",message.get("error"));

        client.disconnect();
        assertTrue(client.waitFor(1000L,State.DISCONNECTED));
    }

    public void testCookies() throws Exception
    {
        BayeuxClient client = new BayeuxClient("http://localhost:"+_port+"/cometd", LongPollingTransport.create(null, _httpClient));
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
        assertTrue(client.waitFor(1000L,State.DISCONNECTED));
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
            final AtomicBoolean connected=new AtomicBoolean();
            final BayeuxClient client=new BayeuxClient("http://localhost:"+_port+"/cometd", LongPollingTransport.create(null, _httpClient));
            final String room="/channel/"+(i%rooms);
            clients[i] = client;

            client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
            {
                public void onMessage(ClientSessionChannel channel, Message message)
                {
                    if (connected.getAndSet(false))
                        connections.decrementAndGet();

                    if (message.isSuccessful())
                    {
                        client.getChannel(room).subscribe(new ClientSessionChannel.MessageListener()
                        {
                            public void onMessage(ClientSessionChannel channel, Message message)
                            {
                                received.incrementAndGet();
                            }
                        });
                    }
                }
            });

            client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
            {
                public void onMessage(ClientSessionChannel channel, Message message)
                {
                    if (!connected.getAndSet(message.isSuccessful()))
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
            assertTrue(client.waitFor(1000L,State.DISCONNECTED));
    }


    public void testPublish() throws Exception
    {
        final BlockingArrayQueue<String> results = new BlockingArrayQueue<String>();

        String channelName = "/chat/msg";
        _bayeux.createIfAbsent(channelName);
        _bayeux.getChannel(channelName).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
            {
                results.add(from.getId());
                results.add(channel.getId());
                results.add(String.valueOf(message.getData()));
                return true;
            }
        });


        BayeuxClient client = new BayeuxClient("http://localhost:"+_port+"/cometd", LongPollingTransport.create(null, _httpClient));
        client.handshake();
        client.getChannel(channelName).publish("Hello World");

        String id=results.poll(1,TimeUnit.SECONDS);
        assertEquals(client.getId(),id);
        assertEquals(channelName,results.poll(1,TimeUnit.SECONDS));
        assertEquals("Hello World",results.poll(1,TimeUnit.SECONDS));

        client.disconnect();
    }

    public void testWaitFor() throws Exception
    {
        final BlockingArrayQueue<String> results = new BlockingArrayQueue<String>();

        String channelName = "/chat/msg";
        _bayeux.createIfAbsent(channelName);
        _bayeux.getChannel(channelName).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
            {
                results.add(from.getId());
                results.add(channel.getId());
                results.add(String.valueOf(message.getData()));
                return true;
            }
        });

        BayeuxClient client = new BayeuxClient("http://localhost:"+_port+"/cometd", LongPollingTransport.create(null, _httpClient));
        long wait = 1000L;
        long start = System.nanoTime();
        client.handshake(wait);
        long stop = System.nanoTime();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(stop - start) < wait);
        assertNotNull(client.getId());
        client.getChannel(channelName).publish("Hello World");

        assertEquals(client.getId(),results.poll(1,TimeUnit.SECONDS));
        assertEquals(channelName,results.poll(1,TimeUnit.SECONDS));
        assertEquals("Hello World",results.poll(1,TimeUnit.SECONDS));

        client.disconnect();
        assertTrue(client.waitFor(1000,State.DISCONNECTED));
    }

    public void testURLWithImplicitPort() throws Exception
    {
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final CountDownLatch latch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient("http://localhost/cometd", LongPollingTransport.create(null, _httpClient))
        {
            @Override
            public void onConnectException(Throwable x)
            {
                if (failure.get() == null)
                {
                    failure.set(x);
                    latch.countDown();
                }
            }
        };
        client.handshake();
        assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        assertNotNull(failure.get());
        assertTrue(failure.get() instanceof java.net.ConnectException);
    }

    public void testAbortThenRestart() throws Exception
    {
        final AtomicReference<CountDownLatch> handshakeLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        BayeuxClient client = new BayeuxClient("http://localhost:"+_port+"/cometd", LongPollingTransport.create(null, _httpClient));
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    handshakeLatch.get().countDown();
            }
        });
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    connectLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        assertTrue(handshakeLatch.get().await(1000, TimeUnit.MILLISECONDS));
        assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));

        client.abort();
        assertFalse(client.isConnected());

        // Restart
        handshakeLatch.set(new CountDownLatch(1));
        connectLatch.set(new CountDownLatch(1));
        client.handshake();
        assertTrue(handshakeLatch.get().await(1000, TimeUnit.MILLISECONDS));
        assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));
        assertTrue(client.isConnected());

        client.disconnect();
    }

    public void testAbortBeforePublishThenRestart() throws Exception
    {
        final String channelName = "/service/test";
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        final CountDownLatch publishLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient("http://localhost:"+_port+"/cometd", LongPollingTransport.create(null, _httpClient))
        {
            @Override
            protected AbstractSessionChannel newChannel(ChannelId channelId)
            {
                return new BayeuxClientChannel(channelId)
                {
                    @Override
                    public void publish(Object data)
                    {
                        abort();
                        super.publish(data);
                    }
                };
            }

            @Override
            public void onSending(Message[] messages)
            {
                if (messages.length == 1 && channelName.equals(messages[0].getChannel()))
                    publishLatch.countDown();
            }

            @Override
            public void onException(Throwable x)
            {
                if (x instanceof IllegalStateException)
                    failureLatch.countDown();
            }
        };
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    connectLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));

        client.getChannel(channelName).publish(new HashMap<String, Object>());
        assertTrue(failureLatch.await(1000, TimeUnit.MILLISECONDS));

        // Publish must not be sent
        assertFalse(publishLatch.await(1000, TimeUnit.MILLISECONDS));
        assertFalse(client.isConnected());

        connectLatch.set(new CountDownLatch(1));
        client.handshake();
        assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));
        // Check that publish has not been queued and is not sent on restart
        assertFalse(publishLatch.await(1000, TimeUnit.MILLISECONDS));

        client.disconnect();
    }

    public void testAbortAfterPublishThenRestart() throws Exception
    {
        final String channelName = "/test";
        final AtomicBoolean abort = new AtomicBoolean(false);
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        BayeuxClient client = new BayeuxClient("http://localhost:"+_port+"/cometd", LongPollingTransport.create(null, _httpClient))
        {
            @Override
            protected void send(Message.Mutable... messages)
            {
                if (messages.length == 1 && channelName.equals(messages[0].getChannel()) && abort.get())
                {
                    abort();
                    publishLatch.get().countDown();
                }
                super.send(messages);
            }
        };
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    connectLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));

        ClientSessionChannel channel = client.getChannel(channelName);
        final AtomicReference<CountDownLatch> messageLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        channel.subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                messageLatch.get().countDown();
            }
        });

        abort.set(true);
        channel.publish(new HashMap<String, Object>());
        assertTrue(publishLatch.get().await(1000, TimeUnit.MILLISECONDS));
        assertFalse(client.isConnected());

        // Message must not be received
        assertFalse(messageLatch.get().await(1000, TimeUnit.MILLISECONDS));

        connectLatch.set(new CountDownLatch(1));
        client.handshake();
        assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));

        client.disconnect();
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
