package org.cometd.java.annotation;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerChannelImpl;
import org.cometd.server.ServerSessionImpl;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ServerAnnotationProcessorTest
{
    private static BayeuxServerImpl bayeuxServer;
    private static ServerAnnotationProcessor processor;

    @BeforeClass
    public static void init() throws Exception
    {
        bayeuxServer = new BayeuxServerImpl();
        bayeuxServer.setOption(BayeuxServerImpl.LOG_LEVEL, "3");
        bayeuxServer.start();
        processor = ServerAnnotationProcessor.get(bayeuxServer);
    }

    @AfterClass
    public static void destroy() throws Exception
    {
        processor.close();
        bayeuxServer.stop();
    }

    @Test
    public void testNull() throws Exception
    {
        boolean processed = processor.configure(null);
        assertFalse(processed);
    }

    @Test
    public void testNonServiceAnnotatedClass() throws Exception
    {
        class S
        {
            @Inject
            private BayeuxServer bayeux;
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertFalse(processed);
        assertNull(s.bayeux);
    }

    @Test
    public void testInjectBayeuxServerOnField() throws Exception
    {
        @Service
        class S
        {
            @Inject
            private BayeuxServer bayeux;
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);
        assertNotNull(s.bayeux);
    }

    @Test
    public void testInjectBayeuxServerOnMethod() throws Exception
    {
        @Service
        class S
        {
            private BayeuxServer bayeux;
            @Inject
            private void setBayeuxServer(BayeuxServer bayeuxServer)
            {
                this.bayeux = bayeuxServer;
            }
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);
        assertNotNull(s.bayeux);
    }

    @Test
    public void testInjectLocalSessionOnField() throws Exception
    {
        @Service
        class S
        {
            @Session
            private LocalSession localSession;
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);
        assertNotNull(s.localSession);
    }

    @Test
    public void testInjectLocalSessionOnMethod() throws Exception
    {
        @Service
        class S
        {
            private LocalSession localSession;
            @Session
            private void set(LocalSession localSession)
            {
                this.localSession = localSession;
            }
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);
        assertNotNull(s.localSession);
    }

    @Test
    public void testInjectServerSessionOnField() throws Exception
    {
        @Service
        class S
        {
            @Session
            private ServerSession serverSession;
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);
        assertNotNull(s.serverSession);
    }

    @Test
    public void testInjectServerSessionOnMethod() throws Exception
    {
        @Service
        class S
        {
            private ServerSession serverSession;
            @Session
            private void set(ServerSession serverSession)
            {
                this.serverSession = serverSession;
            }
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);
        assertNotNull(s.serverSession);
    }

    @Test
    public void testInjectLocalSessionAndServerSession() throws Exception
    {
        @Service
        class S
        {
            @Session
            private LocalSession localSession;
            @Session
            private ServerSession serverSession;
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);
        assertNotNull(s.localSession);
        assertNotNull(s.serverSession);
        assertSame(s.localSession.getServerSession(), s.serverSession);
    }

    @Test
    public void testListenUnlisten() throws Exception
    {
        final AtomicReference<ServerSession> sessionRef = new AtomicReference<ServerSession>();
        final AtomicReference<ServerMessage> messageRef = new AtomicReference<ServerMessage>();

        @Service
        class S
        {
            @Listener("/foo")
            private void foo(ServerSession remote, ServerMessage.Mutable message)
            {
                assertNotNull(remote);
                assertNotNull(message);
                sessionRef.set(remote);
                messageRef.set(message);
            }
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);
        ServerChannel channel = bayeuxServer.getChannel("/foo");
        assertNotNull(channel);
        assertEquals(1, ((ServerChannelImpl)channel).getListeners().size());

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel(channel.getId());
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertNotNull(sessionRef.get());
        assertSame(sessionRef.get(), remote.getServerSession());
        assertNotNull(messageRef.get());

        processed = processor.deconfigureCallbacks(s);
        assertTrue(processed);

        // Fake another publish
        sessionRef.set(null);
        messageRef.set(null);
        message = bayeuxServer.newMessage();
        message.setChannel(channel.getId());
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertNull(sessionRef.get());
        assertNull(messageRef.get());

        // Be sure the channel is removed after few sweeps
        for (int i = 0; i < 3; ++i)
            bayeuxServer.doSweep();
        assertNull(bayeuxServer.getChannel(channel.getId()));
    }

    @Test
    public void testListenerPublishingOnOwnChannelDoesNotReceive() throws Exception
    {
        final AtomicInteger counter = new AtomicInteger();

        @Service
        class S
        {
            @Inject
            private BayeuxServer bayeuxServer;
            @Session
            private ServerSession serverSession;

            @Listener("/foo/*")
            private void foo(ServerSession remote, ServerMessage.Mutable message)
            {
                int count = counter.incrementAndGet();

                String channelName = "/foo/own";
                bayeuxServer.createIfAbsent(channelName);

                // This callback should be called only once, triggered by the client's publish
                // However if the Listener.receiveOwnPublishes attribute is not taken in account
                // this callback is called again, and we want to test that this does not happen.
                if (count == 1)
                    bayeuxServer.getChannel(channelName).publish(serverSession, new HashMap(), null);
            }
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar");
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertEquals(1, counter.get());
    }

    @Test
    public void testListenerPublishingOnOwnChannelReceives() throws Exception
    {
        final AtomicInteger counter = new AtomicInteger();

        @Service
        class S
        {
            @Inject
            private BayeuxServer bayeuxServer;
            @Session
            private ServerSession serverSession;

            @Listener(value = "/foo/*", receiveOwnPublishes = true)
            private void foo(ServerSession remote, ServerMessage.Mutable message)
            {
                counter.incrementAndGet();
                String channelName = "/foo/own";
                bayeuxServer.createIfAbsent(channelName);
                if (!channelName.equals(message.getChannel()))
                    bayeuxServer.getChannel(channelName).publish(serverSession, new HashMap(), null);
            }
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar");
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertEquals(2, counter.get());
    }

    @Test
    public void testSubscribeUnsubscribe() throws Exception
    {
        final AtomicReference<Message> messageRef = new AtomicReference<Message>();

        @Service
        class S
        {
            @Session
            private LocalSession serverSession;

            @Subscription("/foo/**")
            private void foo(Message message)
            {
                messageRef.set(message);
            }
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar/baz");
        message.setData(new HashMap());
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertNotNull(messageRef.get());

        processed = processor.deconfigureCallbacks(s);
        assertTrue(processed);

        // Fake another publish
        messageRef.set(null);
        message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar/baz");
        message.setData(new HashMap());
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertNull(messageRef.get());
    }

    @Test
    public void testListenerOnOverriddenMethod() throws Exception
    {
        final CountDownLatch messageLatch = new CountDownLatch(2);

        @Service
        class S
        {
            @Listener("/foo")
            protected void foo(ServerSession remote, ServerMessage.Mutable message)
            {
                messageLatch.countDown();
            }
        }

        class SS extends S
        {
            @Override
            protected void foo(ServerSession remote, ServerMessage.Mutable message)
            {
                super.foo(remote, message);
                messageLatch.countDown();
            }
        }

        SS ss = new SS();
        boolean processed = processor.configure(ss);
        assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo");
        message.setData(new HashMap());
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertTrue(messageLatch.await(1000, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testListenerMethodWithCovariantParameters() throws Exception
    {
        final CountDownLatch messageLatch = new CountDownLatch(1);

        @Service
        class S
        {
            @Listener("/foo")
            protected void foo(org.cometd.bayeux.Session remote, Message message)
            {
                messageLatch.countDown();
            }
        }

        S s = new S();
        boolean processed = processor.configure(s);
        assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo");
        message.setData(new HashMap());
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertTrue(messageLatch.await(1000, TimeUnit.MILLISECONDS));
    }
}
