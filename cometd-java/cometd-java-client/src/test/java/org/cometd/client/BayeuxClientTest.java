package org.cometd.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
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
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient.State;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.HashMapMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometdServlet;
import org.cometd.server.DefaultSecurityPolicy;
import org.eclipse.jetty.client.ContentExchange;
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
    private boolean _stress = Boolean.getBoolean("STRESS");
    private Server _server;
    private Random _random = new Random();
    private HttpClient _httpClient;
    private TestFilter _filter;
    private String _cometdURL;
    private BayeuxServerImpl _bayeux;
    private SelectChannelConnector _connector;
    private ServletContextHandler _context;
    String _servletPath = "/cometd";
    String _contextPath = "";

    @Override
    protected void setUp() throws Exception
    {
        _server = new Server();

        _connector = new SelectChannelConnector();
        _connector.setMaxIdleTime(30000);
        _server.addConnector(_connector);

        _context = new ServletContextHandler(_server, _contextPath);
        _context.setBaseResource(Resource.newResource("./src/test"));

        // Test Filter
        _filter = new TestFilter();
        _context.addFilter(new FilterHolder(_filter), "/*", 0);

        // Cometd servlet
        ServletHolder cometd_holder = new ServletHolder(CometdServlet.class);
        cometd_holder.setInitParameter("timeout", "10000");
        cometd_holder.setInitParameter("interval", "100");
        cometd_holder.setInitParameter("maxInterval", "100000");
        cometd_holder.setInitParameter("multiFrameInterval", "2000");
        cometd_holder.setInitParameter("logLevel", "3");
        cometd_holder.setInitOrder(1);

        _context.addServlet(cometd_holder, _servletPath + "/*");
        _context.addServlet(DefaultServlet.class, "/");

        _server.start();

        _httpClient = new HttpClient();
        _httpClient.setMaxConnectionsPerAddress(20000);
        _httpClient.setIdleTimeout(15000);
        _httpClient.start();

        _cometdURL = "http://localhost:" + _connector.getLocalPort() + _contextPath + _servletPath;

        _bayeux = (BayeuxServerImpl)_context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
    }

    @Override
    protected void tearDown() throws Exception
    {
        _httpClient.stop();

        _server.stop();
        _server.join();
    }

    public void testBatchingAfterHandshake() throws Exception
    {
        final BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient));

        client.setDebugEnabled(false);

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

        final String channelName = "/foo/bar";
        client.batch(new Runnable()
        {
            public void run()
            {
                // Subscribe and publish must be batched so that they are sent in order,
                // otherwise it's possible that the subscribe arrives to the server after the publish
                client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
                {
                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        System.err.println("message " + message);
                        messages.add(channel.getId());
                        messages.add(message.getData().toString());
                    }
                });
                client.getChannel(channelName).publish("Hello");
            }
        });

        assertTrue(client.waitFor(1000, BayeuxClient.State.CONNECTED));

        assertEquals(channelName, messages.poll(1, TimeUnit.SECONDS));
        assertEquals("Hello", messages.poll(1, TimeUnit.SECONDS));

        client.disconnect();
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    public void testHandshakeFailsBeforeSend() throws Exception
    {
        LongPollingTransport transport = new LongPollingTransport(null, _httpClient)
        {
            @Override
            protected void customize(ContentExchange exchange)
            {
                super.customize(exchange);
                // Remove the address so that the send will fail
                exchange.setAddress(null);
            }
        };
        final AtomicReference<CountDownLatch> latch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        BayeuxClient client = new BayeuxClient(_cometdURL, transport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Suppress logging of expected exception
                if (!(x instanceof UnknownHostException))
                    super.onFailure(x, messages);
            }
        };

        client.setDebugEnabled(false);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                assertFalse(message.isSuccessful());
                latch.get().countDown();
            }
        });
        client.handshake();
        assertTrue(latch.get().await(1000, TimeUnit.MILLISECONDS));

        // Be sure it backoffs and retries
        latch.set(new CountDownLatch(1));
        assertTrue(latch.get().await(client.getBackoffIncrement() * 2, TimeUnit.MILLISECONDS));

        client.disconnect();

        // Be sure it does not retry
        latch.set(new CountDownLatch(1));
        assertFalse(latch.get().await(client.getBackoffIncrement() * 3, TimeUnit.MILLISECONDS));
    }

    public void testHandshakeFailsBadTransport() throws Exception
    {
        LongPollingTransport transport = new LongPollingTransport(null, _httpClient)
        {
            @Override
            protected void customize(ContentExchange exchange)
            {
                super.customize(exchange);
                // Modify the exchange so that the server chokes it
                exchange.setMethod("PUT");
            }
        };
        final AtomicReference<CountDownLatch> latch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        BayeuxClient client = new BayeuxClient(_cometdURL, transport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Suppress logging of expected exception
                if (!(x instanceof ProtocolException))
                    super.onFailure(x, messages);
            }
        };

        client.setDebugEnabled(false);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                assertFalse(message.isSuccessful());
                latch.get().countDown();
            }
        });
        client.handshake();
        assertTrue(latch.get().await(1000, TimeUnit.MILLISECONDS));

        // Be sure it backoffs and retries
        latch.set(new CountDownLatch(1));
        assertTrue(latch.get().await(client.getBackoffIncrement() * 2, TimeUnit.MILLISECONDS));

        client.disconnect();

        // Be sure it does not retry
        latch.set(new CountDownLatch(1));
        assertFalse(latch.get().await(client.getBackoffIncrement() * 3, TimeUnit.MILLISECONDS));
    }

    public void testHandshakeDenied() throws Exception
    {
        BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient));

        client.setDebugEnabled(false);
        SecurityPolicy oldPolicy = _bayeux.getSecurityPolicy();
        _bayeux.setSecurityPolicy(new DefaultSecurityPolicy()
        {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
            {
                return false;
            }
        });
        try
        {
            final AtomicReference<CountDownLatch> latch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
            client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
            {
                public void onMessage(ClientSessionChannel channel, Message message)
                {
                    assertFalse(message.isSuccessful());
                    latch.get().countDown();
                }
            });
            client.handshake();
            assertTrue(latch.get().await(1000, TimeUnit.MILLISECONDS));

            // Be sure it does not retry
            latch.set(new CountDownLatch(1));
            assertFalse(latch.get().await(client.getBackoffIncrement() * 2, TimeUnit.MILLISECONDS));

            assertEquals(BayeuxClient.State.DISCONNECTED, client.getState());
        }
        finally
        {
            _bayeux.setSecurityPolicy(oldPolicy);
        }
    }

    public void testHandshakeFailsNoTransports() throws Exception
    {
        final AtomicReference<Message> handshake = new AtomicReference<Message>();
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        final CountDownLatch connectLatch = new CountDownLatch(1);

        final BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient))
        {
            @Override
            protected void processHandshake(Message.Mutable message)
            {
                // Force no transports
                message.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, new Object[0]);
                super.processHandshake(message);
            }

            @Override
            protected boolean sendConnect()
            {
                boolean result = super.sendConnect();
                connectLatch.countDown();
                return result;
            }
        };

        client.setDebugEnabled(false);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                handshake.set(message);
                handshakeLatch.countDown();
            }
        });
        client.handshake();
        assertTrue(handshakeLatch.await(1000, TimeUnit.MILLISECONDS));

        assertFalse(handshake.get().isSuccessful());
        assertTrue(handshake.get().containsKey(Message.ERROR_FIELD));
        assertEquals(BayeuxClient.State.DISCONNECTED, client.getState());

        // Be sure the connect is not tried
        assertFalse(connectLatch.await(1000, TimeUnit.MILLISECONDS));
    }

    public void testHandshakeRetries() throws Exception
    {
        final BlockingArrayQueue<Message> queue = new BlockingArrayQueue<Message>(100, 100);
        final AtomicBoolean connected = new AtomicBoolean(false);
        BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient))
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                Message.Mutable problem = newMessage();
                problem.setSuccessful(false);
                problem.put("exception", x);
                queue.offer(problem);
            }
        };

        client.setDebugEnabled(false);

        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(message.isSuccessful());
                if (message.isSuccessful())
                    queue.offer(message);
            }
        });

        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connected.set(false);
                if (message.isSuccessful())
                    queue.offer(message);
            }
        });

        client.getChannel("/**").addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel session, Message message)
            {
                if (message.getData() != null || Channel.META_SUBSCRIBE.equals(message.getChannel()) || Channel.META_DISCONNECT.equals(message.getChannel()))
                {
                    queue.offer(message);
                }
            }
        });

        long backoffIncrement = 1000L;
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, backoffIncrement);
        _filter._code = 503;
        client.handshake();

        Message message = queue.poll(backoffIncrement, TimeUnit.MILLISECONDS);
        assertFalse(message.isSuccessful());
        Object exception = message.get("exception");
        assertTrue(exception instanceof ProtocolException);

        message = queue.poll(2000+ 2 * backoffIncrement, TimeUnit.MILLISECONDS);
        assertFalse(message.isSuccessful());
        exception = message.get("exception");
        assertTrue(exception instanceof ProtocolException);

        message = queue.poll(2000+ 3 * backoffIncrement, TimeUnit.MILLISECONDS);
        assertFalse(message.isSuccessful());
        exception = message.get("exception");
        assertTrue(exception instanceof ProtocolException);

        _filter._code = 0;

        message = queue.poll(2000+ 4 * backoffIncrement, TimeUnit.MILLISECONDS);
        assertTrue(message.isSuccessful());
        assertEquals(Channel.META_HANDSHAKE, message.getChannel());

        client.disconnect();
        assertTrue(client.waitFor(1000L, State.DISCONNECTED));
    }

    public void testConnectRetries() throws Exception
    {
        final AtomicInteger connects = new AtomicInteger();
        final CountDownLatch attempts = new CountDownLatch(4);
        final BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient))
        {
            @Override
            protected boolean scheduleConnect(long interval, long backoff)
            {
                int count = connects.get();
                if (count > 0)
                {
                    assertEquals((count - 1) * getBackoffIncrement(), backoff);
                    attempts.countDown();
                }
                return super.scheduleConnect(interval, backoff);
            }

            @Override
            protected boolean sendConnect()
            {
                if (connects.incrementAndGet() < 2)
                    return super.sendConnect();

                Message.Mutable connect = newMessage();
                connect.setChannel(Channel.META_CONNECT);
                connect.setSuccessful(false);
                processConnect(connect);
                return false;
            }
        };

        client.setDebugEnabled(false);
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    connectLatch.get().countDown();
            }
        });
        client.handshake();
        assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));

        // It should backoff and retry
        // Wait for 2 attempts, which will happen at +1s and +3s (doubled for safety)
        assertTrue(attempts.await(client.getBackoffIncrement() * 8, TimeUnit.MILLISECONDS));

        client.disconnect();
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    public void testPerf() throws Exception
    {
        Runtime.getRuntime().addShutdownHook(new DumpThread());

        final int rooms = _stress ? 100 : 10;
        final int publish = _stress ? 4000 : 100;
        final int batch = _stress ? 10 : 2;
        final int pause = _stress ? 50 : 10;
        BayeuxClient[] clients = new BayeuxClient[_stress ? 500 : 2 * rooms];

        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger received = new AtomicInteger();

        for (int i = 0; i < clients.length; i++)
        {
            final AtomicBoolean connected = new AtomicBoolean();
            final BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient));

        client.setDebugEnabled(false);
            final String room = "/channel/" + (i % rooms);
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

        long start = System.currentTimeMillis();
        Thread.sleep(100);
        while (connections.get() < clients.length && (System.currentTimeMillis() - start) < 10000)
        {
            Thread.sleep(1000);
            System.err.println("connected " + connections.get() + "/" + clients.length);
        }

        assertEquals(clients.length, connections.get());

        long start0 = System.currentTimeMillis();
        for (int i = 0; i < publish; i++)
        {
            final int sender = _random.nextInt(clients.length);
            final String channel = "/channel/" + _random.nextInt(rooms);

            String data = "data from " + sender + " to " + channel;
            // System.err.println(data);
            clients[sender].getChannel(channel).publish(data, "" + i);

            if (i % batch == (batch - 1))
            {
                System.err.print('.');
                Thread.sleep(pause);
            }
            if (i % 1000 == 999)
                System.err.println();
        }
        System.err.println();

        int expected = clients.length * publish / rooms;

        start = System.currentTimeMillis();
        while (received.get() < expected && (System.currentTimeMillis() - start) < 10000)
        {
            Thread.sleep(1000);
            System.err.println("received " + received.get() + "/" + expected);
        }
        System.err.println((received.get() * 1000) / (System.currentTimeMillis() - start0) + " m/s");

        assertEquals(expected, received.get());

        for (BayeuxClient client : clients)
            client.disconnect();

        for (BayeuxClient client : clients)
            assertTrue(client.waitFor(1000L, State.DISCONNECTED));
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

        BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient));

        client.setDebugEnabled(false);
        client.handshake();
        assertTrue(client.waitFor(10000, State.CONNECTED));

        String data = "Hello World";
        client.getChannel(channelName).publish(data);

        String id = results.poll(10, TimeUnit.SECONDS);
        assertEquals(client.getId(), id);
        assertEquals(channelName, results.poll(10, TimeUnit.SECONDS));
        assertEquals(data, results.poll(10, TimeUnit.SECONDS));

        client.disconnect();
        assertTrue(client.waitFor(10000, State.DISCONNECTED));
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

        BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient));

        client.setDebugEnabled(false);
        long wait = 1000L;
        long start = System.nanoTime();
        client.handshake(wait);
        long stop = System.nanoTime();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(stop - start) < wait);
        assertNotNull(client.getId());
        String data = "Hello World";
        client.getChannel(channelName).publish(data);

        assertEquals(client.getId(), results.poll(1, TimeUnit.SECONDS));
        assertEquals(channelName, results.poll(1, TimeUnit.SECONDS));
        assertEquals(data, results.poll(1, TimeUnit.SECONDS));

        client.disconnect();
        assertTrue(client.waitFor(1000, State.DISCONNECTED));
    }

    public void testURLWithImplicitPort() throws Exception
    {
        final AtomicBoolean listening = new AtomicBoolean();
        try
        {
            Socket socket = new Socket("localhost", 80);
            socket.close();
            listening.set(true);
        }
        catch (ConnectException x)
        {
            listening.set(false);
        }

        final CountDownLatch latch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient("http://localhost/cometd", LongPollingTransport.create(null, _httpClient))
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Do not call super to suppress error logging
            }
        };

        client.setDebugEnabled(false);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                assertFalse(message.isSuccessful());

                // If port 80 is listening, it's probably some other HTTP server
                // and a bayeux request will result in a 404, which is converted
                // to a ProtocolException; if not listening, it will be a ConnectException
                if (listening.get())
                    assertTrue(message.get("exception") instanceof ProtocolException);
                else
                    assertTrue(message.get("exception") instanceof ConnectException);
                latch.countDown();
            }
        });
        client.handshake();
        assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
        client.disconnect();
        assertTrue(client.waitFor(5000, State.DISCONNECTED));
    }

    public void testAbortThenRestart() throws Exception
    {
        final AtomicReference<CountDownLatch> handshakeLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient));

        client.setDebugEnabled(false);
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
        assertTrue(handshakeLatch.get().await(100000, TimeUnit.MILLISECONDS));
        assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));
        assertTrue(client.isConnected());

        client.disconnect();
        assertTrue(client.waitFor(1000, State.DISCONNECTED));
    }

    public void testAbortBeforePublishThenRestart() throws Exception
    {
        final String channelName = "/service/test";
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        final CountDownLatch publishLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient))
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
            protected boolean sendMessages(Message.Mutable... messages)
            {
                boolean result = super.sendMessages(messages);
                if (result)
                    publishLatch.countDown();
                return result;
            }
        };

        client.setDebugEnabled(false);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    connectLatch.get().countDown();
            }
        });
        client.getChannel(channelName).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    failureLatch.countDown();
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
        assertTrue(client.waitFor(1000, State.DISCONNECTED));
    }

    public void testAbortAfterPublishThenRestart() throws Exception
    {
        final String channelName = "/test";
        final AtomicBoolean abort = new AtomicBoolean(false);
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient))
        {
            @Override
            protected boolean sendMessages(Message.Mutable... messages)
            {
                abort();
                publishLatch.get().countDown();
                return super.sendMessages(messages);
            }
        };

        client.setDebugEnabled(false);
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
        assertTrue(publishLatch.get().await(10, TimeUnit.SECONDS));
        assertFalse(client.isConnected());

        // Message must not be received
        assertFalse(messageLatch.get().await(10, TimeUnit.SECONDS));

        connectLatch.set(new CountDownLatch(1));
        client.handshake();
        assertTrue(connectLatch.get().await(10, TimeUnit.SECONDS));

        client.disconnect();
        assertTrue(client.waitFor(10000, State.DISCONNECTED));
    }

    public void testRestart() throws Exception
    {
        BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient))
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                Log.ignore(x);
            }
            
        };
        
        final AtomicReference<CountDownLatch> connectedLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> disconnectedLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    connectedLatch.get().countDown();
                else
                    disconnectedLatch.get().countDown();
            }
        });
        client.handshake();

        // Wait for connect
        assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        assertTrue(client.isConnected());
        
        // Stop server
        int port = _connector.getLocalPort();
        disconnectedLatch.set(new CountDownLatch(1));
        _server.stop();
        assertTrue(disconnectedLatch.get().await(10, TimeUnit.SECONDS));
        assertTrue(!client.isConnected());
        
        // restart server
        _connector.setPort(port);
        connectedLatch.set(new CountDownLatch(1));
        _server.start();
        
        // Wait for connect
        assertTrue(connectedLatch.get().await(10, TimeUnit.SECONDS));
        assertTrue(client.isConnected());
        
        Thread.sleep(10000);
    }
    
    public void testAuthentication() throws Exception
    {
        final AtomicReference<String> sessionId = new AtomicReference<String>();
        class A extends DefaultSecurityPolicy implements ServerSession.RemoveListener
        {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
            {
                Map<String, Object> ext = message.getExt();
                if (ext == null)
                    return false;

                Object authn = ext.get("authentication");
                if (!(authn instanceof Map))
                    return false;

                @SuppressWarnings("unchecked")
                Map<String, Object> authentication = (Map<String, Object>)authn;

                String token = (String)authentication.get("token");
                if (token == null)
                    return false;

                sessionId.set(session.getId());
                session.addListener(this);

                return true;
            }

            public void removed(ServerSession session, boolean timeout)
            {
                sessionId.set(null);
            }
        }
        A authenticator = new A();

        SecurityPolicy oldPolicy = _bayeux.getSecurityPolicy();
        _bayeux.setSecurityPolicy(authenticator);
        try
        {
            BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient));

        client.setDebugEnabled(false);

            Map<String, Object> authentication = new HashMap<String, Object>();
            authentication.put("token", "1234567890");
            Message.Mutable fields = new HashMapMessage();
            fields.getExt(true).put("authentication", authentication);
            client.handshake(fields);

            assertTrue(client.waitFor(1000, State.CONNECTED));

            assertEquals(client.getId(), sessionId.get());

            client.disconnect();
            assertTrue(client.waitFor(1000, State.DISCONNECTED));

            assertNull(sessionId.get());
        }
        finally
        {
            _bayeux.setSecurityPolicy(oldPolicy);
        }
    }

    public void testClient() throws Exception
    {
        final BlockingArrayQueue<Object> results = new BlockingArrayQueue<Object>();

        BayeuxClient client = new BayeuxClient(_cometdURL, LongPollingTransport.create(null, _httpClient));

        client.setDebugEnabled(false);

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
                System.err.println("<<" + message + " @ " + channel);
                results.offer(message);
            }
        });

        client.handshake();

        Message message = (Message)results.poll(1, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        assertTrue(message.isSuccessful());
        String id = client.getId();
        assertNotNull(id);

        message = (Message)results.poll(1, TimeUnit.SECONDS);
        assertEquals(Channel.META_CONNECT, message.getChannel());
        assertTrue(message.isSuccessful());

        ClientSessionChannel.MessageListener subscriber = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                System.err.println("a<" + message + " @ " + channel);
                results.offer(message);
            }
        };
        ClientSessionChannel aChannel = client.getChannel("/a/channel");
        aChannel.subscribe(subscriber);

        message = (Message)results.poll(1, TimeUnit.SECONDS);
        assertEquals(Channel.META_SUBSCRIBE, message.getChannel());
        assertTrue(message.isSuccessful());

        String data = "data";
        aChannel.publish(data);
        message = (Message)results.poll(1, TimeUnit.SECONDS);
        assertEquals(data, message.getData());

        aChannel.unsubscribe(subscriber);
        message = (Message)results.poll(1, TimeUnit.SECONDS);
        assertEquals(Channel.META_UNSUBSCRIBE, message.getChannel());
        assertTrue(message.isSuccessful());

        client.disconnect();
        assertTrue(client.waitFor(1000, BayeuxClient.State.DISCONNECTED));
    }

    public void testStateUnSubscribes() throws Exception
    {
        final BlockingArrayQueue<Object> results = new BlockingArrayQueue<Object>();

        final AtomicBoolean failHandShake = new AtomicBoolean(true);

        LongPollingTransport transport = new LongPollingTransport(null, _httpClient)
        {
            @Override
            protected void customize(ContentExchange exchange)
            {
                super.customize(exchange);

                if (failHandShake.compareAndSet(true, false))
                    exchange.setAddress(null);
            }
        };

        BayeuxClient client = new BayeuxClient(_cometdURL, transport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
            }
        };

        client.setDebugEnabled(true);

        client.getChannel("/meta/*").addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                results.offer(message);
            }
        });

        client.handshake();

        // Subscribe without waiting
        client.getChannel("/foo/bar").subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });

        // First handshake fails
        Message message = (Message)results.poll(10, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        assertFalse(message.isSuccessful());

        // Second handshake works
        message = (Message)results.poll(10, TimeUnit.SECONDS);
        assertNotNull(message);
        assertEquals(Channel.META_HANDSHAKE, message.getChannel());
        assertTrue(message.isSuccessful());
        String id = client.getId();
        assertNotNull(id);

        boolean subscribe = false;
        boolean connect = false;
        for (int i = 0; i < 2; ++i)
        {
            message = (Message)results.poll(10, TimeUnit.SECONDS);
            assertNotNull(message);
            subscribe |= Channel.META_SUBSCRIBE.equals(message.getChannel());
            connect |= Channel.META_CONNECT.equals(message.getChannel());
        }
        assertTrue(subscribe);
        assertTrue(connect);

        // Subscribe again
        client.getChannel("/foo/bar").subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });

        // No second subscribe sent, be sure to wait less than the timeout
        // otherwise we get a connect message
        message = (Message)results.poll(5, TimeUnit.SECONDS);
        assertNull(message);

        client.disconnect();
        boolean disconnect = false;
        connect = false;
        for (int i = 0; i < 2; ++i)
        {
            message = (Message)results.poll(10, TimeUnit.SECONDS);
            assertNotNull(message);
            disconnect |= Channel.META_DISCONNECT.equals(message.getChannel());
            connect |= Channel.META_CONNECT.equals(message.getChannel());
        }
        assertTrue(disconnect);
        assertTrue(connect);
        assertTrue(client.waitFor(10000, BayeuxClient.State.DISCONNECTED));

        // Rehandshake
        client.handshake();
        assertTrue(client.waitFor(10000, BayeuxClient.State.CONNECTED));

        results.clear();
        // Subscribe again
        client.getChannel("/foo/bar").subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });

        // Subscribe is sent, skip the connect message if present
        message = (Message)results.poll(10, TimeUnit.SECONDS);
        assertNotNull(message);
        if (Channel.META_CONNECT.equals(message.getChannel()))
            message = (Message)results.poll(10, TimeUnit.SECONDS);
        assertEquals(Channel.META_SUBSCRIBE, message.getChannel());

        // Restart server
        int port = _connector.getLocalPort();
        _server.stop();
        _server.join();
        assertTrue(client.waitFor(10000, BayeuxClient.State.UNCONNECTED));
        _connector.setPort(port);
        _server.start();

        _bayeux = (BayeuxServerImpl)_context.getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        assertTrue(client.waitFor(10000, BayeuxClient.State.CONNECTED));
        results.clear();

        // Subscribe again
        client.getChannel("/foo/bar").subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });

        // Subscribe is sent, skip the connect message if present
        message = (Message)results.poll(10, TimeUnit.SECONDS);
        assertNotNull(message);
        if (Channel.META_CONNECT.equals(message.getChannel()))
            message = (Message)results.poll(10, TimeUnit.SECONDS);
        assertEquals(Channel.META_SUBSCRIBE, message.getChannel());

        client.disconnect();
        assertTrue(client.waitFor(10000, BayeuxClient.State.DISCONNECTED));
    }

    private class DumpThread extends Thread
    {
        public void run()
        {
            try
            {
                if (_server != null) _server.dump();
                if (_httpClient != null) _httpClient.dump();
            }
            catch (Exception x)
            {
                x.printStackTrace();
            }
        }
    }

    private static class TestFilter implements Filter
    {
        volatile int _code = 0;

        public void destroy()
        {
        }

        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            if (_code != 0)
                ((HttpServletResponse)response).sendError(_code);
            else
                chain.doFilter(request, response);
        }

        public void init(FilterConfig filterConfig) throws ServletException
        {
        }
    }
}
