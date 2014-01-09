/*
 * Copyright (c) 2008-2014 the original author or authors.
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
package org.cometd.annotation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ServerAnnotationProcessorTest
{
    private BayeuxServerImpl bayeuxServer;
    private ServerAnnotationProcessor processor;

    @Before
    public void init() throws Exception
    {
        bayeuxServer = new BayeuxServerImpl();
        bayeuxServer.start();
        processor = new ServerAnnotationProcessor(bayeuxServer);
    }

    @After
    public void destroy() throws Exception
    {
        bayeuxServer.stop();
    }

    @Test
    public void testNull() throws Exception
    {
        boolean processed = processor.process(null);
        assertFalse(processed);
    }

    @Test
    public void testNonServiceAnnotatedClass() throws Exception
    {
        NonServiceAnnotatedService s = new NonServiceAnnotatedService();
        boolean processed = processor.process(s);
        assertFalse(processed);
        assertNull(s.bayeux);
    }

    public static class NonServiceAnnotatedService
    {
        @Inject
        private BayeuxServer bayeux;
    }

    @Test
    public void testInjectBayeuxServerOnField() throws Exception
    {
        InjectBayeuxServerOnFieldService s = new InjectBayeuxServerOnFieldService();
        boolean processed = processor.process(s);
        assertTrue(processed);
        assertNotNull(s.bayeux);
    }

    @Service
    public static class InjectBayeuxServerOnFieldService
    {
        @Inject
        private BayeuxServer bayeux;
    }

    @Test
    public void testInjectBayeuxServerOnMethod() throws Exception
    {
        InjectBayeuxServerOnMethodService s = new InjectBayeuxServerOnMethodService();
        boolean processed = processor.process(s);
        assertTrue(processed);
        assertNotNull(s.bayeux);
    }

    @Service
    public static class InjectBayeuxServerOnMethodService
    {
        private BayeuxServer bayeux;

        @Inject
        private void setBayeuxServer(BayeuxServer bayeuxServer)
        {
            this.bayeux = bayeuxServer;
        }
    }

    @Test
    public void testInjectLocalSessionOnField() throws Exception
    {
        InjectLocalSessionOnFieldService s = new InjectLocalSessionOnFieldService();
        boolean processed = processor.process(s);
        assertTrue(processed);
        assertNotNull(s.localSession);
    }

    @Service
    public static class InjectLocalSessionOnFieldService
    {
        @Session
        private LocalSession localSession;
    }

    @Test
    public void testInjectLocalSessionOnMethod() throws Exception
    {
        InjectLocalSessionOnMethodService s = new InjectLocalSessionOnMethodService();
        boolean processed = processor.process(s);
        assertTrue(processed);
        assertNotNull(s.localSession);
    }

    @Service
    public static class InjectLocalSessionOnMethodService
    {
        private LocalSession localSession;

        @Session
        private void set(LocalSession localSession)
        {
            this.localSession = localSession;
        }
    }

    @Test
    public void testInjectServerSessionOnField() throws Exception
    {
        InjectServerSessionOnFieldService s = new InjectServerSessionOnFieldService();
        boolean processed = processor.process(s);
        assertTrue(processed);
        assertNotNull(s.serverSession);
    }

    @Service
    public static class InjectServerSessionOnFieldService
    {
        @Session
        private ServerSession serverSession;
    }

    @Test
    public void testInjectServerSessionOnMethod() throws Exception
    {
        InjectServerSessionOnMethodService s = new InjectServerSessionOnMethodService();
        boolean processed = processor.process(s);
        assertTrue(processed);
        assertNotNull(s.serverSession);
    }

    @Service
    public static class InjectServerSessionOnMethodService
    {
        private ServerSession serverSession;

        @Session
        private void set(ServerSession serverSession)
        {
            this.serverSession = serverSession;
        }
    }

    @Test
    public void testInjectLocalSessionAndServerSession() throws Exception
    {
        InjectLocalSessionAndServerSessionService s = new InjectLocalSessionAndServerSessionService();
        boolean processed = processor.process(s);
        assertTrue(processed);
        assertNotNull(s.localSession);
        assertNotNull(s.serverSession);
        assertSame(s.localSession.getServerSession(), s.serverSession);
    }

    @Service
    public static class InjectLocalSessionAndServerSessionService
    {
        @Session
        private LocalSession localSession;
        @Session
        private ServerSession serverSession;
    }

    @Test
    public void testListenUnlisten() throws Exception
    {
        final AtomicReference<ServerSession> sessionRef = new AtomicReference<>();
        final AtomicReference<ServerMessage> messageRef = new AtomicReference<>();

        ListenUnlistenService s = new ListenUnlistenService(sessionRef, messageRef);
        boolean processed = processor.process(s);
        assertTrue(processed);
        ServerChannel channel = bayeuxServer.getChannel("/foo");
        assertNotNull(channel);
        assertEquals(1, channel.getListeners().size());

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel(channel.getId());
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertNotNull(sessionRef.get());
        assertSame(sessionRef.get(), remote.getServerSession());
        assertNotNull(messageRef.get());

        processed = processor.deprocessCallbacks(s);
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
            bayeuxServer.sweep();
        assertNull(bayeuxServer.getChannel(channel.getId()));
    }

    @Service
    public static class ListenUnlistenService
    {
        private final AtomicReference<ServerSession> sessionRef;
        private final AtomicReference<ServerMessage> messageRef;

        public ListenUnlistenService(AtomicReference<ServerSession> sessionRef, AtomicReference<ServerMessage> messageRef)
        {
            this.sessionRef = sessionRef;
            this.messageRef = messageRef;
        }

        @Listener("/foo")
        public void foo(ServerSession remote, ServerMessage.Mutable message)
        {
            assertNotNull(remote);
            assertNotNull(message);
            sessionRef.set(remote);
            messageRef.set(message);
        }
    }

    @Test
    public void testListenerPublishingOnOwnChannelDoesNotReceive() throws Exception
    {
        final AtomicInteger counter = new AtomicInteger();

        ListenerPublishingOnOwnChannelDoesNotReceiveService s = new ListenerPublishingOnOwnChannelDoesNotReceiveService(counter);
        boolean processed = processor.process(s);
        assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar");
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertEquals(1, counter.get());
    }

    @Service
    public static class ListenerPublishingOnOwnChannelDoesNotReceiveService
    {
        private final AtomicInteger counter;
        @Inject
        private BayeuxServer bayeuxServer;
        @Session
        private ServerSession serverSession;

        public ListenerPublishingOnOwnChannelDoesNotReceiveService(AtomicInteger counter)
        {
            this.counter = counter;
        }

        @Listener("/foo/*")
        public void foo(ServerSession remote, ServerMessage.Mutable message)
        {
            int count = counter.incrementAndGet();

            String channelName = "/foo/own";
            MarkedReference<ServerChannel> channel = bayeuxServer.createChannelIfAbsent(channelName);

            // This callback should be called only once, triggered by the client's publish
            // However if the Listener.receiveOwnPublishes attribute is not taken in account
            // this callback is called again, and we want to test that this does not happen.
            if (count == 1)
                channel.getReference().publish(serverSession, new HashMap<>());
        }
    }

    @Test
    public void testListenerPublishingOnOwnChannelReceives() throws Exception
    {
        final AtomicInteger counter = new AtomicInteger();

        ListenerPublishingOnOwnChannelReceivesService s = new ListenerPublishingOnOwnChannelReceivesService(counter);
        boolean processed = processor.process(s);
        assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar");
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertEquals(2, counter.get());
    }

    @Service
    public static class ListenerPublishingOnOwnChannelReceivesService
    {
        private final AtomicInteger counter;
        @Inject
        private BayeuxServer bayeuxServer;
        @Session
        private ServerSession serverSession;

        public ListenerPublishingOnOwnChannelReceivesService(AtomicInteger counter)
        {
            this.counter = counter;
        }

        @Listener(value = "/foo/*", receiveOwnPublishes = true)
        public void foo(ServerSession remote, ServerMessage.Mutable message)
        {
            counter.incrementAndGet();
            String channelName = "/foo/own";
            MarkedReference<ServerChannel> channel = bayeuxServer.createChannelIfAbsent(channelName);
            if (!channelName.equals(message.getChannel()))
                channel.getReference().publish(serverSession, new HashMap<>());
        }
    }

    @Test
    public void testSubscribeUnsubscribe() throws Exception
    {
        final AtomicReference<Message> messageRef = new AtomicReference<>();

        SubscribeUnsubscribeService s = new SubscribeUnsubscribeService(messageRef);
        boolean processed = processor.process(s);
        assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar/baz");
        message.setData(new HashMap<>());
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertNotNull(messageRef.get());

        processed = processor.deprocessCallbacks(s);
        assertTrue(processed);

        // Fake another publish
        messageRef.set(null);
        message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar/baz");
        message.setData(new HashMap<>());
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertNull(messageRef.get());
    }

    @Service
    public static class SubscribeUnsubscribeService
    {
        private final AtomicReference<Message> messageRef;
        @Session
        private LocalSession serverSession;

        public SubscribeUnsubscribeService(AtomicReference<Message> messageRef)
        {
            this.messageRef = messageRef;
        }

        @Subscription("/foo/**")
        public void foo(Message message)
        {
            messageRef.set(message);
        }
    }

    @Test
    public void testListenerOnOverriddenMethod() throws Exception
    {
        final CountDownLatch messageLatch = new CountDownLatch(2);

        DerivedListenerOnOverriddenMethodService ss = new DerivedListenerOnOverriddenMethodService(messageLatch);
        boolean processed = processor.process(ss);
        assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        remote.getChannel("/foo").publish(new HashSet<>());

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class ListenerOnOverriddenMethodService
    {
        protected final CountDownLatch messageLatch;

        public ListenerOnOverriddenMethodService(CountDownLatch messageLatch)
        {

            this.messageLatch = messageLatch;
        }

        @Listener("/foo")
        public void foo(ServerSession remote, ServerMessage.Mutable message)
        {
            messageLatch.countDown();
        }
    }

    public static class DerivedListenerOnOverriddenMethodService extends ListenerOnOverriddenMethodService
    {
        public DerivedListenerOnOverriddenMethodService(CountDownLatch messageLatch)
        {
            super(messageLatch);
        }

        @Override
        public void foo(ServerSession remote, ServerMessage.Mutable message)
        {
            super.foo(remote, message);
            messageLatch.countDown();
        }
    }

    @Test
    public void testListenerMethodWithCovariantParameters() throws Exception
    {
        final CountDownLatch messageLatch = new CountDownLatch(1);

        ListenerMethodWithCovariantParametersService s = new ListenerMethodWithCovariantParametersService(messageLatch);
        boolean processed = processor.process(s);
        assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo");
        message.setData(new HashMap());
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class ListenerMethodWithCovariantParametersService
    {
        private final CountDownLatch messageLatch;

        public ListenerMethodWithCovariantParametersService(CountDownLatch messageLatch)
        {
            this.messageLatch = messageLatch;
        }

        @Listener("/foo")
        public void foo(org.cometd.bayeux.Session remote, Message message)
        {
            messageLatch.countDown();
        }
    }

    @Test
    public void testListenerMethodReturningNewBooleanFalse() throws Exception
    {
        final CountDownLatch messageLatch = new CountDownLatch(1);

        ListenerMethodReturningNewBooleanFalseService s = new ListenerMethodReturningNewBooleanFalseService(messageLatch);
        boolean processed = processor.process(s);
        assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo");
        message.setData(new HashMap());
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message);

        assertFalse(messageLatch.await(1, TimeUnit.SECONDS));
    }

    @Service
    public static class ListenerMethodReturningNewBooleanFalseService
    {
        private final CountDownLatch messageLatch;

        public ListenerMethodReturningNewBooleanFalseService(CountDownLatch messageLatch)
        {
            this.messageLatch = messageLatch;
        }

        @Listener("/foo")
        public Object foo(ServerSession remote, ServerMessage.Mutable message)
        {
            // Do not unbox it, we are testing exactly this case
            return new Boolean(false);
        }

        @Subscription("/foo")
        public void foo(Message message)
        {
            messageLatch.countDown();
        }
    }

    @Test
    public void testLifecycleMethodsWithWrongReturnType() throws Exception
    {
        LifecycleMethodsWithWrongReturnTypeService s = new LifecycleMethodsWithWrongReturnTypeService();

        try
        {
            processor.processPostConstruct(s);
            fail();
        }
        catch (RuntimeException x)
        {
        }

        try
        {
            processor.processPreDestroy(s);
            fail();
        }
        catch (RuntimeException x)
        {
        }
    }

    @Service
    public static class LifecycleMethodsWithWrongReturnTypeService
    {
        @PostConstruct
        public Object init()
        {
            return null;
        }

        @PreDestroy
        public Object destroy()
        {
            return null;
        }
    }

    @Test
    public void testLifecycleMethodsWithWrongParameters() throws Exception
    {
        LifecycleMethodsWithWrongParametersService s = new LifecycleMethodsWithWrongParametersService();

        try
        {
            processor.processPostConstruct(s);
            fail();
        }
        catch (RuntimeException x)
        {
        }

        try
        {
            processor.processPreDestroy(s);
            fail();
        }
        catch (RuntimeException x)
        {
        }
    }

    @Service
    public static class LifecycleMethodsWithWrongParametersService
    {
        @PostConstruct
        public void init(Object param)
        {
        }

        @PreDestroy
        public void destroy(Object param)
        {
        }
    }

    @Test
    public void testLifecycleMethodsWithStaticModifier() throws Exception
    {
        S s = new S();

        try
        {
            processor.processPostConstruct(s);
            fail();
        }
        catch (RuntimeException x)
        {
        }

        try
        {
            processor.processPreDestroy(s);
            fail();
        }
        catch (RuntimeException x)
        {
        }
    }

    @Test
    public void testMultipleLifecycleMethodsInSameClass() throws Exception
    {
        MultipleLifecycleMethodsInSameClassService s = new MultipleLifecycleMethodsInSameClassService();

        try
        {
            processor.processPostConstruct(s);
            fail();
        }
        catch (RuntimeException x)
        {
        }

        try
        {
            processor.processPreDestroy(s);
            fail();
        }
        catch (RuntimeException x)
        {
        }
    }

    @Service
    public static class MultipleLifecycleMethodsInSameClassService
    {
        @PostConstruct
        public void init1()
        {
        }

        @PostConstruct
        public void init2()
        {
        }

        @PreDestroy
        public void destroy1()
        {
        }

        @PreDestroy
        public void destroy2()
        {
        }
    }

    @Test
    public void testInitDestroy() throws Exception
    {
        final CountDownLatch initLatch = new CountDownLatch(1);
        final CountDownLatch destroyLatch = new CountDownLatch(1);

        InitDestroyService s = new InitDestroyService(initLatch, destroyLatch);

        processor.process(s);
        assertTrue(initLatch.await(5, TimeUnit.SECONDS));

        processor.deprocess(s);
        assertTrue(destroyLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class InitDestroyService
    {
        private final CountDownLatch initLatch;
        private final CountDownLatch destroyLatch;

        public InitDestroyService(CountDownLatch initLatch, CountDownLatch destroyLatch)
        {
            this.initLatch = initLatch;
            this.destroyLatch = destroyLatch;
        }

        @PostConstruct
        public void init()
        {
            initLatch.countDown();
        }

        @PreDestroy
        public void destroy()
        {
            destroyLatch.countDown();
        }
    }

    @Test
    public void testInitInSuperClass() throws Exception
    {
        final CountDownLatch initLatch = new CountDownLatch(1);
        final CountDownLatch destroyLatch = new CountDownLatch(1);

        DerivedInitInSuperClassService ss = new DerivedInitInSuperClassService(initLatch, destroyLatch);

        processor.process(ss);
        assertTrue(initLatch.await(5, TimeUnit.SECONDS));

        processor.deprocess(ss);
        assertTrue(destroyLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class InitInSuperClassService
    {
        private final CountDownLatch initLatch;

        public InitInSuperClassService(CountDownLatch initLatch)
        {
            this.initLatch = initLatch;
        }

        @PostConstruct
        protected void init()
        {
            initLatch.countDown();
        }
    }

    public class DerivedInitInSuperClassService extends InitInSuperClassService
    {
        private final CountDownLatch destroyLatch;

        public DerivedInitInSuperClassService(CountDownLatch initLatch, CountDownLatch destroyLatch)
        {
            super(initLatch);
            this.destroyLatch = destroyLatch;
        }

        @PreDestroy
        private void destroy()
        {
            destroyLatch.countDown();
        }
    }

    @Test
    public void testInitOverridden() throws Exception
    {
        final CountDownLatch initLatch = new CountDownLatch(2);
        final CountDownLatch destroyLatch = new CountDownLatch(1);

        DerivedInitOverriddenService ss = new DerivedInitOverriddenService(initLatch, destroyLatch);

        processor.process(ss);
        assertTrue(initLatch.await(5, TimeUnit.SECONDS));

        processor.deprocess(ss);
        assertTrue(destroyLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class InitOverriddenService
    {
        protected final CountDownLatch initLatch;

        public InitOverriddenService(CountDownLatch initLatch)
        {
            this.initLatch = initLatch;
        }

        @PostConstruct
        public void init()
        {
            assertEquals(1, initLatch.getCount());
            initLatch.countDown();
        }
    }

    public static class DerivedInitOverriddenService extends InitOverriddenService
    {
        private final CountDownLatch destroyLatch;

        public DerivedInitOverriddenService(CountDownLatch initLatch, CountDownLatch destroyLatch)
        {
            super(initLatch);
            this.destroyLatch = destroyLatch;
        }

        @Override
        public void init()
        {
            assertEquals(2, initLatch.getCount());
            initLatch.countDown();
            super.init();
        }

        @PreDestroy
        void destroy()
        {
            destroyLatch.countDown();
        }
    }

    @Test
    public void testMultipleLifecycleMethodsInDifferentClasses() throws Exception
    {
        final CountDownLatch initLatch = new CountDownLatch(2);
        final CountDownLatch destroyLatch = new CountDownLatch(2);

        DerivedMultipleLifecycleMethodsInDifferentClassesService ss = new DerivedMultipleLifecycleMethodsInDifferentClassesService(initLatch, destroyLatch);

        processor.process(ss);
        assertTrue(initLatch.await(5, TimeUnit.SECONDS));

        processor.deprocess(ss);
        assertTrue(destroyLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class MultipleLifecycleMethodsInDifferentClassesService
    {
        protected final CountDownLatch initLatch;
        protected final CountDownLatch destroyLatch;

        public MultipleLifecycleMethodsInDifferentClassesService(CountDownLatch initLatch, CountDownLatch destroyLatch)
        {
            this.initLatch = initLatch;
            this.destroyLatch = destroyLatch;
        }

        @PostConstruct
        public void init1()
        {
            assertEquals(2, initLatch.getCount());
            initLatch.countDown();
        }

        @PreDestroy
        public void destroy1()
        {
            assertEquals(1, destroyLatch.getCount());
            destroyLatch.countDown();
        }
    }

    public class DerivedMultipleLifecycleMethodsInDifferentClassesService extends MultipleLifecycleMethodsInDifferentClassesService
    {
        public DerivedMultipleLifecycleMethodsInDifferentClassesService(CountDownLatch initLatch, CountDownLatch destroyLatch)
        {
            super(initLatch, destroyLatch);
        }

        @PostConstruct
        public void init2()
        {
            assertEquals(1, initLatch.getCount());
            initLatch.countDown();
        }

        @PreDestroy
        public void destroy2()
        {
            assertEquals(2, destroyLatch.getCount());
            destroyLatch.countDown();
        }
    }

    @Test
    public void testConfigureDefault() throws Exception
    {
        final Set<String> configured = new HashSet<>();

        ConfigureDefaultService s = new ConfigureDefaultService(configured);
        boolean processed = processor.process(s);
        assertTrue(processed);

        assertTrue(configured.contains("/foo/bar"));
        assertTrue(configured.contains("/blah"));
        assertTrue(configured.contains("/halb"));

        ConfigureDefaultService s2 = new ConfigureDefaultService(configured);
        try
        {
            processor.process(s2);
            fail();
        }
        catch (IllegalStateException expected)
        {
        }
    }

    @Service
    public static class ConfigureDefaultService
    {
        private final Set<String> configured;

        public ConfigureDefaultService(Set<String> configured)
        {
            this.configured = configured;
        }

        @Configure(value = "/foo/bar")
        private void configureFooBar(ConfigurableServerChannel channel)
        {
            configured.add(channel.getId());
        }

        @Configure(value = {"/blah", "/halb"})
        private void configureBlah(ConfigurableServerChannel channel)
        {
            configured.add(channel.getId());
        }
    }

    @Test
    public void testConfigureNoErrorIfExists() throws Exception
    {
        final List<String> configured = new ArrayList<>();

        ConfigureNoErrorIfExistsService s1 = new ConfigureNoErrorIfExistsService(configured);
        boolean processed = processor.process(s1);
        assertTrue(processed);
        ConfigureNoErrorIfExistsService s2 = new ConfigureNoErrorIfExistsService(configured);
        processed = processor.process(s2);
        assertTrue(processed);

        assertEquals(1, configured.size());
        assertEquals("/foo", configured.get(0));
    }

    @Service
    public static class ConfigureNoErrorIfExistsService
    {
        private final List<String> configured;

        public ConfigureNoErrorIfExistsService(List<String> configured)
        {
            this.configured = configured;
        }

        @Configure(value = "/foo", errorIfExists = false)
        private void configureFooBar(ConfigurableServerChannel channel)
        {
            configured.add(channel.getId());
        }
    }

    @Test
    public void testConfigureConfigureIfExists() throws Exception
    {
        final List<String> configured = new ArrayList<>();

        ConfigureConfigureIfExistsService s1 = new ConfigureConfigureIfExistsService(configured);
        boolean processed = processor.process(s1);
        assertTrue(processed);
        ConfigureConfigureIfExistsService s2 = new ConfigureConfigureIfExistsService(configured);
        processed = processor.process(s2);
        assertTrue(processed);

        assertEquals(2, configured.size());
        assertEquals("/foo", configured.get(0));
        assertEquals("/foo", configured.get(1));
    }

    @Service
    public static class ConfigureConfigureIfExistsService
    {
        private final List<String> configured;

        public ConfigureConfigureIfExistsService(List<String> configured)
        {
            this.configured = configured;
        }

        @Configure(value = "/foo", configureIfExists = true)
        private void configureFooBar(ConfigurableServerChannel channel)
        {
            configured.add(channel.getId());
        }
    }

    @Test
    public void testInjectables() throws Exception
    {
        Injectables i = new DerivedInjectables();
        InjectablesService s = new InjectablesService();
        processor = new ServerAnnotationProcessor(bayeuxServer, i);
        boolean processed = processor.process(s);
        assertTrue(processed);

        assertSame(i, s.i);
    }

    class Injectables
    {
    }

    class DerivedInjectables extends Injectables
    {
    }

    @Service
    public static class InjectablesService
    {
        @Inject
        private Injectables i;
    }

    @Service
    private static class S
    {
        @PostConstruct
        public static void init()
        {
        }

        @PreDestroy
        public static void destroy()
        {
        }
    }
}
