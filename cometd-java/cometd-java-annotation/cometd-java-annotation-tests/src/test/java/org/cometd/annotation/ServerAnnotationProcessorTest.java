/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.cometd.annotation.server.Configure;
import org.cometd.annotation.server.RemoteCall;
import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.MarkedReference;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ServerAnnotationProcessorTest {
    private BayeuxServerImpl bayeuxServer;
    private ServerAnnotationProcessor processor;

    @BeforeEach
    public void init() throws Exception {
        bayeuxServer = new BayeuxServerImpl();
        bayeuxServer.start();
        processor = new ServerAnnotationProcessor(bayeuxServer);
    }

    @AfterEach
    public void destroy() throws Exception {
        bayeuxServer.stop();
    }

    @Test
    public void testNull() {
        boolean processed = processor.process(null);
        Assertions.assertFalse(processed);
    }

    @Test
    public void testNonServiceAnnotatedClass() {
        NonServiceAnnotatedService s = new NonServiceAnnotatedService();
        boolean processed = processor.process(s);
        Assertions.assertFalse(processed);
        Assertions.assertNull(s.bayeux);
    }

    public static class NonServiceAnnotatedService {
        @Inject
        private BayeuxServer bayeux;
    }

    @Test
    public void testInjectBayeuxServerOnField() {
        InjectBayeuxServerOnFieldService s = new InjectBayeuxServerOnFieldService();
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);
        Assertions.assertNotNull(s.bayeux);
    }

    @Service
    public static class InjectBayeuxServerOnFieldService {
        @Inject
        private BayeuxServer bayeux;
    }

    @Test
    public void testInjectBayeuxServerOnMethod() {
        InjectBayeuxServerOnMethodService s = new InjectBayeuxServerOnMethodService();
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);
        Assertions.assertNotNull(s.bayeux);
    }

    @Service
    public static class InjectBayeuxServerOnMethodService {
        private BayeuxServer bayeux;

        @Inject
        private void setBayeuxServer(BayeuxServer bayeuxServer) {
            this.bayeux = bayeuxServer;
        }
    }

    @Test
    public void testInjectBayeuxServerOnOverriddenMethod() {
        DerivedInjectBayeuxServerOnOverriddenMethodService s = new DerivedInjectBayeuxServerOnOverriddenMethodService();
        boolean processed = processor.process(s);
        Assertions.assertFalse(processed);
        Assertions.assertNull(s.bayeux);
    }

    @Service
    public static class InjectBayeuxServerOnOverriddenMethodService {
        protected BayeuxServer bayeux;

        @Inject
        protected void setBayeuxServer(BayeuxServer bayeuxServer) {
            this.bayeux = bayeuxServer;
        }
    }

    public static class DerivedInjectBayeuxServerOnOverriddenMethodService extends InjectBayeuxServerOnOverriddenMethodService {
        // Overrides without annotation.
        @Override
        protected void setBayeuxServer(BayeuxServer bayeuxServer) {
            super.setBayeuxServer(bayeuxServer);
        }
    }

    @Test
    public void testInjectBayeuxServerOnOverridingMethod() {
        DerivedInjectBayeuxServerOnOverridingMethodService s = new DerivedInjectBayeuxServerOnOverridingMethodService();
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);
        Assertions.assertNotNull(s.bayeux);
    }

    @Service
    public static class InjectBayeuxServerOnOverridingMethodService {
        protected BayeuxServer bayeux;

        @Inject
        protected void setBayeuxServer(BayeuxServer bayeuxServer) {
            this.bayeux = bayeuxServer;
        }
    }

    public static class DerivedInjectBayeuxServerOnOverridingMethodService extends InjectBayeuxServerOnOverridingMethodService {
        // Overrides with annotation.
        @Override
        @Inject
        protected void setBayeuxServer(BayeuxServer bayeuxServer) {
            super.setBayeuxServer(bayeuxServer);
        }
    }

    @Test
    public void testInjectLocalSessionOnField() {
        InjectLocalSessionOnFieldService s = new InjectLocalSessionOnFieldService();
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);
        Assertions.assertNotNull(s.localSession);
    }

    @Service
    public static class InjectLocalSessionOnFieldService {
        @Session
        private LocalSession localSession;
    }

    @Test
    public void testInjectLocalSessionOnMethod() {
        InjectLocalSessionOnMethodService s = new InjectLocalSessionOnMethodService();
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);
        Assertions.assertNotNull(s.localSession);
    }

    @Service
    public static class InjectLocalSessionOnMethodService {
        private LocalSession localSession;

        @Session
        private void set(LocalSession localSession) {
            this.localSession = localSession;
        }
    }

    @Test
    public void testInjectLocalSessionOnOverriddenMethod() {
        DerivedInjectLocalSessionOnOverriddenMethodService s = new DerivedInjectLocalSessionOnOverriddenMethodService();
        boolean processed = processor.process(s);
        Assertions.assertFalse(processed);
        Assertions.assertNull(s.localSession);
    }

    @Service
    public static class InjectLocalSessionOnOverriddenMethodService {
        protected LocalSession localSession;

        @Session
        protected void set(LocalSession localSession) {
            this.localSession = localSession;
        }
    }

    public static class DerivedInjectLocalSessionOnOverriddenMethodService extends InjectLocalSessionOnOverriddenMethodService {
        // Overrides without annotation.
        @Override
        protected void set(LocalSession localSession) {
            super.set(localSession);
        }
    }

    @Test
    public void testInjectLocalSessionOnOverridingMethod() {
        DerivedInjectLocalSessionOnOverridingMethodService s = new DerivedInjectLocalSessionOnOverridingMethodService();
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);
        Assertions.assertNotNull(s.localSession);
    }

    @Service
    public static class InjectLocalSessionOnOverridingMethodService {
        protected LocalSession localSession;

        @Session
        protected void set(LocalSession localSession) {
            this.localSession = localSession;
        }
    }

    public static class DerivedInjectLocalSessionOnOverridingMethodService extends InjectLocalSessionOnOverridingMethodService {
        // Overrides with annotation.
        @Override
        @Session
        protected void set(LocalSession localSession) {
            super.set(localSession);
        }
    }

    @Test
    public void testInjectServerSessionOnField() {
        InjectServerSessionOnFieldService s = new InjectServerSessionOnFieldService();
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);
        Assertions.assertNotNull(s.serverSession);
    }

    @Service
    public static class InjectServerSessionOnFieldService {
        @Session
        private ServerSession serverSession;
    }

    @Test
    public void testInjectServerSessionOnMethod() {
        InjectServerSessionOnMethodService s = new InjectServerSessionOnMethodService();
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);
        Assertions.assertNotNull(s.serverSession);
    }

    @Service
    public static class InjectServerSessionOnMethodService {
        private ServerSession serverSession;

        @Session
        private void set(ServerSession serverSession) {
            this.serverSession = serverSession;
        }
    }

    @Test
    public void testInjectLocalSessionAndServerSession() {
        InjectLocalSessionAndServerSessionService s = new InjectLocalSessionAndServerSessionService();
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);
        Assertions.assertNotNull(s.localSession);
        Assertions.assertNotNull(s.serverSession);
        Assertions.assertSame(s.localSession.getServerSession(), s.serverSession);
    }

    @Service
    public static class InjectLocalSessionAndServerSessionService {
        @Session
        private LocalSession localSession;
        @Session
        private ServerSession serverSession;
    }

    @Test
    public void testListenUnlisten() throws Exception {
        AtomicReference<ServerSession> sessionRef = new AtomicReference<>();
        AtomicReference<ServerMessage> messageRef = new AtomicReference<>();

        ListenUnlistenService s = new ListenUnlistenService(sessionRef, messageRef);
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);
        ServerChannel channel = bayeuxServer.getChannel("/foo");
        Assertions.assertNotNull(channel);
        Assertions.assertEquals(1, channel.getListeners().size());

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel(channel.getId());
        message.setClientId(remote.getId());
        process(remote, message);

        Assertions.assertNotNull(sessionRef.get());
        Assertions.assertSame(sessionRef.get(), remote.getServerSession());
        Assertions.assertNotNull(messageRef.get());

        processed = processor.deprocessCallbacks(s);
        Assertions.assertTrue(processed);

        // Fake another publish
        sessionRef.set(null);
        messageRef.set(null);
        message = bayeuxServer.newMessage();
        message.setChannel(channel.getId());
        message.setClientId(remote.getId());
        process(remote, message);

        Assertions.assertNull(sessionRef.get());
        Assertions.assertNull(messageRef.get());

        // Be sure the channel is removed after few sweeps
        for (int i = 0; i < 3; ++i) {
            bayeuxServer.sweep();
        }
        Assertions.assertNull(bayeuxServer.getChannel(channel.getId()));
    }

    @Service
    public static class ListenUnlistenService {
        private final AtomicReference<ServerSession> sessionRef;
        private final AtomicReference<ServerMessage> messageRef;

        public ListenUnlistenService(AtomicReference<ServerSession> sessionRef, AtomicReference<ServerMessage> messageRef) {
            this.sessionRef = sessionRef;
            this.messageRef = messageRef;
        }

        @Listener("/foo")
        public void foo(ServerSession remote, ServerMessage.Mutable message) {
            Assertions.assertNotNull(remote);
            Assertions.assertNotNull(message);
            sessionRef.set(remote);
            messageRef.set(message);
        }
    }

    @Test
    public void testListenerPublishingOnOwnChannelDoesNotReceive() throws Exception {
        AtomicInteger counter = new AtomicInteger();

        ListenerPublishingOnOwnChannelDoesNotReceiveService s = new ListenerPublishingOnOwnChannelDoesNotReceiveService(counter);
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar");
        message.setClientId(remote.getId());
        process(remote, message);

        Assertions.assertEquals(1, counter.get());
    }

    @Service
    public static class ListenerPublishingOnOwnChannelDoesNotReceiveService {
        private final AtomicInteger counter;
        @Inject
        private BayeuxServer bayeuxServer;
        @Session
        private ServerSession serverSession;

        public ListenerPublishingOnOwnChannelDoesNotReceiveService(AtomicInteger counter) {
            this.counter = counter;
        }

        @Listener("/foo/*")
        public void foo(ServerSession remote, ServerMessage.Mutable message) {
            int count = counter.incrementAndGet();

            String channelName = "/foo/own";
            MarkedReference<ServerChannel> channel = bayeuxServer.createChannelIfAbsent(channelName);

            // This callback should be called only once, triggered by the client's publish
            // However if the Listener.receiveOwnPublishes attribute is not taken in account
            // this callback is called again, and we want to test that this does not happen.
            if (count == 1) {
                channel.getReference().publish(serverSession, new HashMap<>(), Promise.noop());
            }
        }
    }

    @Test
    public void testListenerPublishingOnOwnChannelReceives() throws Exception {
        AtomicInteger counter = new AtomicInteger();

        ListenerPublishingOnOwnChannelReceivesService s = new ListenerPublishingOnOwnChannelReceivesService(counter);
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar");
        message.setClientId(remote.getId());
        process(remote, message);

        Assertions.assertEquals(2, counter.get());
    }

    @Service
    public static class ListenerPublishingOnOwnChannelReceivesService {
        private final AtomicInteger counter;
        @Inject
        private BayeuxServer bayeuxServer;
        @Session
        private ServerSession serverSession;

        public ListenerPublishingOnOwnChannelReceivesService(AtomicInteger counter) {
            this.counter = counter;
        }

        @Listener(value = "/foo/*", receiveOwnPublishes = true)
        public void foo(ServerSession remote, ServerMessage.Mutable message) {
            counter.incrementAndGet();
            String channelName = "/foo/own";
            MarkedReference<ServerChannel> channel = bayeuxServer.createChannelIfAbsent(channelName);
            if (!channelName.equals(message.getChannel())) {
                channel.getReference().publish(serverSession, new HashMap<>(), Promise.noop());
            }
        }
    }

    @Test
    public void testSubscribeUnsubscribe() throws Exception {
        AtomicReference<Message> messageRef = new AtomicReference<>();

        SubscribeUnsubscribeService s = new SubscribeUnsubscribeService(messageRef);
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar/baz");
        message.setData(new HashMap<>());
        message.setClientId(remote.getId());
        process(remote, message);

        Assertions.assertNotNull(messageRef.get());

        processed = processor.deprocessCallbacks(s);
        Assertions.assertTrue(processed);

        // Fake another publish
        messageRef.set(null);
        message = bayeuxServer.newMessage();
        message.setChannel("/foo/bar/baz");
        message.setData(new HashMap<>());
        message.setClientId(remote.getId());
        process(remote, message);

        Assertions.assertNull(messageRef.get());
    }

    @Service
    public static class SubscribeUnsubscribeService {
        private final AtomicReference<Message> messageRef;
        @Session
        private LocalSession serverSession;

        public SubscribeUnsubscribeService(AtomicReference<Message> messageRef) {
            this.messageRef = messageRef;
        }

        @Subscription("/foo/**")
        public void foo(Message message) {
            messageRef.set(message);
        }
    }

    @Test
    public void testListenerOnOverriddenMethod() throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(2);

        DerivedListenerOnOverriddenMethodService ss = new DerivedListenerOnOverriddenMethodService(messageLatch);
        boolean processed = processor.process(ss);
        Assertions.assertFalse(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        remote.getChannel("/foo").publish(new HashSet<>());

        Assertions.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));
        Assertions.assertEquals(2, messageLatch.getCount());
    }

    @Service
    public static class ListenerOnOverriddenMethodService {
        protected final CountDownLatch messageLatch;

        public ListenerOnOverriddenMethodService(CountDownLatch messageLatch) {
            this.messageLatch = messageLatch;
        }

        @Listener("/foo")
        public void foo(ServerSession remote, ServerMessage.Mutable message) {
            messageLatch.countDown();
        }
    }

    public static class DerivedListenerOnOverriddenMethodService extends ListenerOnOverriddenMethodService {
        public DerivedListenerOnOverriddenMethodService(CountDownLatch messageLatch) {
            super(messageLatch);
        }

        // Overrides without annotation.
        @Override
        public void foo(ServerSession remote, ServerMessage.Mutable message) {
            super.foo(remote, message);
            messageLatch.countDown();
        }
    }

    @Test
    public void testListenerOnOverridingMethod() throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(2);

        DerivedListenerOnOverridingMethodService ss = new DerivedListenerOnOverridingMethodService(messageLatch);
        boolean processed = processor.process(ss);
        Assertions.assertTrue(processed);

        Assertions.assertEquals(1, bayeuxServer.getChannel("/foo").getListeners().size());

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        remote.getChannel("/foo").publish(new HashSet<>());

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class ListenerOnOverridingMethodService {
        protected final CountDownLatch messageLatch;

        public ListenerOnOverridingMethodService(CountDownLatch messageLatch) {
            this.messageLatch = messageLatch;
        }

        @Listener("/foo")
        public void foo(ServerSession remote, ServerMessage.Mutable message) {
            messageLatch.countDown();
        }
    }

    public static class DerivedListenerOnOverridingMethodService extends ListenerOnOverridingMethodService {
        public DerivedListenerOnOverridingMethodService(CountDownLatch messageLatch) {
            super(messageLatch);
        }

        // Overridden with annotation.
        @Override
        @Listener("/foo")
        public void foo(ServerSession remote, ServerMessage.Mutable message) {
            super.foo(remote, message);
            messageLatch.countDown();
        }
    }

    @Test
    public void testSubscriptionOnOverriddenMethod() throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(2);

        DerivedSubscriptionOnOverriddenMethodService ss = new DerivedSubscriptionOnOverriddenMethodService(messageLatch);
        boolean processed = processor.process(ss);
        Assertions.assertFalse(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        remote.getChannel("/foo").publish(new HashSet<>());

        Assertions.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));
        Assertions.assertEquals(2, messageLatch.getCount());
    }

    @Service
    public static class SubscriptionOnOverriddenMethodService {
        protected final CountDownLatch messageLatch;

        public SubscriptionOnOverriddenMethodService(CountDownLatch messageLatch) {
            this.messageLatch = messageLatch;
        }

        @Subscription("/foo")
        public void foo(Message message) {
            messageLatch.countDown();
        }
    }

    public static class DerivedSubscriptionOnOverriddenMethodService extends SubscriptionOnOverriddenMethodService {
        public DerivedSubscriptionOnOverriddenMethodService(CountDownLatch messageLatch) {
            super(messageLatch);
        }

        // Overrides without annotation.
        @Override
        public void foo(Message message) {
            super.foo(message);
            messageLatch.countDown();
        }
    }

    @Test
    public void testSubscriptionOnOverridingMethod() throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(2);

        DerivedSubscriptionOnOverridingMethodService ss = new DerivedSubscriptionOnOverridingMethodService(messageLatch);
        boolean processed = processor.process(ss);
        Assertions.assertTrue(processed);

        Assertions.assertEquals(1, bayeuxServer.getChannel("/foo").getSubscribers().size());

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        remote.getChannel("/foo").publish(new HashSet<>());

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class SubscriptionOnOverridingMethodService {
        protected final CountDownLatch messageLatch;

        public SubscriptionOnOverridingMethodService(CountDownLatch messageLatch) {
            this.messageLatch = messageLatch;
        }

        @Subscription("/foo")
        public void foo(Message message) {
            messageLatch.countDown();
        }
    }

    public static class DerivedSubscriptionOnOverridingMethodService extends SubscriptionOnOverridingMethodService {
        public DerivedSubscriptionOnOverridingMethodService(CountDownLatch messageLatch) {
            super(messageLatch);
        }

        // Overridden with annotation.
        @Override
        @Subscription("/foo")
        public void foo(Message message) {
            super.foo(message);
            messageLatch.countDown();
        }
    }

    @Test
    public void testRemoteCallOnOverriddenMethod() throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(2);

        DerivedRemoteCallOnOverriddenMethodService ss = new DerivedRemoteCallOnOverriddenMethodService(messageLatch);
        boolean processed = processor.process(ss);
        Assertions.assertFalse(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        remote.getChannel("/service/foo").publish(new HashSet<>());

        Assertions.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));
        Assertions.assertEquals(2, messageLatch.getCount());
    }

    @Service
    public static class RemoteCallOnOverriddenMethodService {
        protected final CountDownLatch messageLatch;

        public RemoteCallOnOverriddenMethodService(CountDownLatch messageLatch) {
            this.messageLatch = messageLatch;
        }

        @RemoteCall("/foo")
        public void foo(RemoteCall.Caller caller, Object data) {
            caller.result(data);
            messageLatch.countDown();
        }
    }

    public static class DerivedRemoteCallOnOverriddenMethodService extends RemoteCallOnOverriddenMethodService {
        public DerivedRemoteCallOnOverriddenMethodService(CountDownLatch messageLatch) {
            super(messageLatch);
        }

        // Overrides without annotation.
        @Override
        public void foo(RemoteCall.Caller caller, Object data) {
            super.foo(caller, data);
            messageLatch.countDown();
        }
    }

    @Test
    public void testRemoteCallOnOverridingMethod() throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(2);

        DerivedRemoteCallOnOverridingMethodService ss = new DerivedRemoteCallOnOverridingMethodService(messageLatch);
        boolean processed = processor.process(ss);
        Assertions.assertTrue(processed);

        Assertions.assertEquals(1, bayeuxServer.getChannel("/service/foo").getListeners().size());

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        remote.getChannel("/service/foo").publish(new HashSet<>());

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class RemoteCallOnOverridingMethodService {
        protected final CountDownLatch messageLatch;

        public RemoteCallOnOverridingMethodService(CountDownLatch messageLatch) {
            this.messageLatch = messageLatch;
        }

        @RemoteCall("/foo")
        public void foo(RemoteCall.Caller caller, Object data) {
            caller.result(data);
            messageLatch.countDown();
        }
    }

    public static class DerivedRemoteCallOnOverridingMethodService extends RemoteCallOnOverridingMethodService {
        public DerivedRemoteCallOnOverridingMethodService(CountDownLatch messageLatch) {
            super(messageLatch);
        }

        // Overridden with annotation.
        @Override
        @RemoteCall("/foo")
        public void foo(RemoteCall.Caller caller, Object data) {
            super.foo(caller, data);
            messageLatch.countDown();
        }
    }

    @Test
    public void testListenerMethodWithCovariantParameters() throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(1);

        ListenerMethodWithCovariantParametersService s = new ListenerMethodWithCovariantParametersService(messageLatch);
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo");
        message.setData(new HashMap<>());
        message.setClientId(remote.getId());
        process(remote, message);

        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class ListenerMethodWithCovariantParametersService {
        private final CountDownLatch messageLatch;

        public ListenerMethodWithCovariantParametersService(CountDownLatch messageLatch) {
            this.messageLatch = messageLatch;
        }

        @Listener("/foo")
        public void foo(org.cometd.bayeux.Session remote, Message message) {
            messageLatch.countDown();
        }
    }

    @Test
    public void testListenerMethodReturningNewBooleanFalse() throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(1);

        ListenerMethodReturningNewBooleanFalseService s = new ListenerMethodReturningNewBooleanFalseService(messageLatch);
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel("/foo");
        message.setData(new HashMap<>());
        message.setClientId(remote.getId());
        process(remote, message);

        Assertions.assertFalse(messageLatch.await(1, TimeUnit.SECONDS));
    }

    @Service
    public static class ListenerMethodReturningNewBooleanFalseService {
        private final CountDownLatch messageLatch;

        public ListenerMethodReturningNewBooleanFalseService(CountDownLatch messageLatch) {
            this.messageLatch = messageLatch;
        }

        @Listener("/foo")
        public Object foo(ServerSession remote, ServerMessage.Mutable message) {
            // Do not unbox it, we are testing exactly this case.
            return Boolean.FALSE;
        }

        @Subscription("/foo")
        public void foo(Message message) {
            messageLatch.countDown();
        }
    }

    @Test
    public void testLifecycleMethodsWithWrongReturnType() {
        LifecycleMethodsWithWrongReturnTypeService s = new LifecycleMethodsWithWrongReturnTypeService();

        try {
            processor.processPostConstruct(s);
            Assertions.fail();
        } catch (RuntimeException ignored) {
        }

        try {
            processor.processPreDestroy(s);
            Assertions.fail();
        } catch (RuntimeException ignored) {
        }
    }

    @Service
    public static class LifecycleMethodsWithWrongReturnTypeService {
        @PostConstruct
        public Object init() {
            return null;
        }

        @PreDestroy
        public Object destroy() {
            return null;
        }
    }

    @Test
    public void testLifecycleMethodsWithWrongParameters() {
        LifecycleMethodsWithWrongParametersService s = new LifecycleMethodsWithWrongParametersService();

        try {
            processor.processPostConstruct(s);
            Assertions.fail();
        } catch (RuntimeException ignored) {
        }

        try {
            processor.processPreDestroy(s);
            Assertions.fail();
        } catch (RuntimeException ignored) {
        }
    }

    @Service
    public static class LifecycleMethodsWithWrongParametersService {
        @PostConstruct
        public void init(Object param) {
        }

        @PreDestroy
        public void destroy(Object param) {
        }
    }

    @Test
    public void testLifecycleMethodsWithStaticModifier() {
        S s = new S();

        try {
            processor.processPostConstruct(s);
            Assertions.fail();
        } catch (RuntimeException ignored) {
        }

        try {
            processor.processPreDestroy(s);
            Assertions.fail();
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    public void testMultipleLifecycleMethodsInSameClass() {
        MultipleLifecycleMethodsInSameClassService s = new MultipleLifecycleMethodsInSameClassService();

        try {
            processor.processPostConstruct(s);
            Assertions.fail();
        } catch (RuntimeException ignored) {
        }

        try {
            processor.processPreDestroy(s);
            Assertions.fail();
        } catch (RuntimeException ignored) {
        }
    }

    @Service
    public static class MultipleLifecycleMethodsInSameClassService {
        @PostConstruct
        public void init1() {
        }

        @PostConstruct
        public void init2() {
        }

        @PreDestroy
        public void destroy1() {
        }

        @PreDestroy
        public void destroy2() {
        }
    }

    @Test
    public void testPostConstructPreDestroy() throws Exception {
        CountDownLatch initLatch = new CountDownLatch(1);
        CountDownLatch destroyLatch = new CountDownLatch(1);

        PostConstructPreDestroyService s = new PostConstructPreDestroyService(initLatch, destroyLatch);

        processor.process(s);
        Assertions.assertTrue(initLatch.await(5, TimeUnit.SECONDS));

        processor.deprocess(s);
        Assertions.assertTrue(destroyLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class PostConstructPreDestroyService {
        private final CountDownLatch initLatch;
        private final CountDownLatch destroyLatch;

        public PostConstructPreDestroyService(CountDownLatch initLatch, CountDownLatch destroyLatch) {
            this.initLatch = initLatch;
            this.destroyLatch = destroyLatch;
        }

        @PostConstruct
        public void init() {
            initLatch.countDown();
        }

        @PreDestroy
        public void destroy() {
            destroyLatch.countDown();
        }
    }

    @Test
    public void testPostConstructInSuperClass() throws Exception {
        CountDownLatch initLatch = new CountDownLatch(1);
        CountDownLatch destroyLatch = new CountDownLatch(1);

        DerivedPostConstructInSuperClassService ss = new DerivedPostConstructInSuperClassService(initLatch, destroyLatch);

        processor.process(ss);
        Assertions.assertTrue(initLatch.await(5, TimeUnit.SECONDS));

        processor.deprocess(ss);
        Assertions.assertTrue(destroyLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class PostConstructInSuperClassService {
        private final CountDownLatch initLatch;

        public PostConstructInSuperClassService(CountDownLatch initLatch) {
            this.initLatch = initLatch;
        }

        @PostConstruct
        protected void init() {
            initLatch.countDown();
        }
    }

    public static class DerivedPostConstructInSuperClassService extends PostConstructInSuperClassService {
        private final CountDownLatch destroyLatch;

        public DerivedPostConstructInSuperClassService(CountDownLatch initLatch, CountDownLatch destroyLatch) {
            super(initLatch);
            this.destroyLatch = destroyLatch;
        }

        @PreDestroy
        private void destroy() {
            destroyLatch.countDown();
        }
    }

    @Test
    public void testPostConstructPreDestroyOnOverriddenMethod() throws Exception {
        CountDownLatch initLatch = new CountDownLatch(2);
        CountDownLatch destroyLatch = new CountDownLatch(2);

        DerivedPostConstructPreDestroyOnOverriddenMethodService ss = new DerivedPostConstructPreDestroyOnOverriddenMethodService(initLatch, destroyLatch);

        processor.process(ss);
        Assertions.assertFalse(initLatch.await(1, TimeUnit.SECONDS));

        processor.deprocess(ss);
        Assertions.assertFalse(destroyLatch.await(1, TimeUnit.SECONDS));
    }

    @Service
    public static class PostConstructPreDestroyOnOverriddenMethodService {
        protected final CountDownLatch initLatch;
        protected final CountDownLatch destroyLatch;

        public PostConstructPreDestroyOnOverriddenMethodService(CountDownLatch initLatch, CountDownLatch destroyLatch) {
            this.initLatch = initLatch;
            this.destroyLatch = destroyLatch;
        }

        @PostConstruct
        public void init() {
            Assertions.assertEquals(1, initLatch.getCount());
            initLatch.countDown();
        }

        @PreDestroy
        public void destroy() {
            Assertions.assertEquals(1, destroyLatch.getCount());
            destroyLatch.countDown();
        }
    }

    public static class DerivedPostConstructPreDestroyOnOverriddenMethodService extends PostConstructPreDestroyOnOverriddenMethodService {
        public DerivedPostConstructPreDestroyOnOverriddenMethodService(CountDownLatch initLatch, CountDownLatch destroyLatch) {
            super(initLatch, destroyLatch);
        }

        // Overridden without annotation.
        @Override
        public void init() {
            Assertions.assertEquals(2, initLatch.getCount());
            initLatch.countDown();
            super.init();
        }

        // Overridden without annotation.
        @Override
        public void destroy() {
            Assertions.assertEquals(2, destroyLatch.getCount());
            destroyLatch.countDown();
            super.init();
        }
    }

    @Test
    public void testPostConstructPreDestroyOnOverridingMethod() throws Exception {
        CountDownLatch initLatch = new CountDownLatch(2);
        CountDownLatch destroyLatch = new CountDownLatch(2);

        DerivedPostConstructPreDestroyOnOverridingMethodService ss = new DerivedPostConstructPreDestroyOnOverridingMethodService(initLatch, destroyLatch);

        processor.process(ss);
        Assertions.assertTrue(initLatch.await(5, TimeUnit.SECONDS));

        processor.deprocess(ss);
        Assertions.assertTrue(initLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class PostConstructPreDestroyOnOverridingMethodService {
        protected final CountDownLatch initLatch;
        protected final CountDownLatch destroyLatch;

        public PostConstructPreDestroyOnOverridingMethodService(CountDownLatch initLatch, CountDownLatch destroyLatch) {
            this.initLatch = initLatch;
            this.destroyLatch = destroyLatch;
        }

        @PostConstruct
        public void init() {
            Assertions.assertEquals(1, initLatch.getCount());
            initLatch.countDown();
        }

        @PreDestroy
        public void destroy() {
            Assertions.assertEquals(1, destroyLatch.getCount());
            destroyLatch.countDown();
        }
    }

    public static class DerivedPostConstructPreDestroyOnOverridingMethodService extends PostConstructPreDestroyOnOverridingMethodService {
        public DerivedPostConstructPreDestroyOnOverridingMethodService(CountDownLatch initLatch, CountDownLatch destroyLatch) {
            super(initLatch, destroyLatch);
        }

        // Overriding with annotation.
        @Override
        @PostConstruct
        public void init() {
            Assertions.assertEquals(2, initLatch.getCount());
            initLatch.countDown();
            super.init();
        }

        @Override
        @PreDestroy
        public void destroy() {
            Assertions.assertEquals(2, destroyLatch.getCount());
            destroyLatch.countDown();
            super.destroy();
        }
    }

    @Test
    public void testMultipleLifecycleMethodsInDifferentClasses() throws Exception {
        CountDownLatch initLatch = new CountDownLatch(2);
        CountDownLatch destroyLatch = new CountDownLatch(2);

        DerivedMultipleLifecycleMethodsInDifferentClassesService ss = new DerivedMultipleLifecycleMethodsInDifferentClassesService(initLatch, destroyLatch);

        processor.process(ss);
        Assertions.assertTrue(initLatch.await(5, TimeUnit.SECONDS));

        processor.deprocess(ss);
        Assertions.assertTrue(destroyLatch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class MultipleLifecycleMethodsInDifferentClassesService {
        protected final CountDownLatch initLatch;
        protected final CountDownLatch destroyLatch;

        public MultipleLifecycleMethodsInDifferentClassesService(CountDownLatch initLatch, CountDownLatch destroyLatch) {
            this.initLatch = initLatch;
            this.destroyLatch = destroyLatch;
        }

        @PostConstruct
        public void init1() {
            Assertions.assertEquals(1, initLatch.getCount());
            initLatch.countDown();
        }

        @PreDestroy
        public void destroy1() {
            Assertions.assertEquals(1, destroyLatch.getCount());
            destroyLatch.countDown();
        }
    }

    public static class DerivedMultipleLifecycleMethodsInDifferentClassesService extends MultipleLifecycleMethodsInDifferentClassesService {
        public DerivedMultipleLifecycleMethodsInDifferentClassesService(CountDownLatch initLatch, CountDownLatch destroyLatch) {
            super(initLatch, destroyLatch);
        }

        @PostConstruct
        public void init2() {
            Assertions.assertEquals(2, initLatch.getCount());
            initLatch.countDown();
        }

        @PreDestroy
        public void destroy2() {
            Assertions.assertEquals(2, destroyLatch.getCount());
            destroyLatch.countDown();
        }
    }

    @Test
    public void testConfigureErrorIfExists() {
        Set<String> configured = new HashSet<>();

        ConfigureErrorIfExistsService s = new ConfigureErrorIfExistsService(configured);
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);

        Assertions.assertTrue(configured.contains("/foo/bar"));
        Assertions.assertTrue(configured.contains("/blah"));
        Assertions.assertTrue(configured.contains("/halb"));

        ConfigureErrorIfExistsService s2 = new ConfigureErrorIfExistsService(configured);
        try {
            processor.process(s2);
            Assertions.fail();
        } catch (IllegalStateException expected) {
        }
    }

    @Service
    public static class ConfigureErrorIfExistsService {
        private final Set<String> configured;

        public ConfigureErrorIfExistsService(Set<String> configured) {
            this.configured = configured;
        }

        @Configure(value = "/foo/bar")
        private void configureFooBar(ConfigurableServerChannel channel) {
            configured.add(channel.getId());
        }

        @Configure(value = {"/blah", "/halb"})
        private void configureBlah(ConfigurableServerChannel channel) {
            configured.add(channel.getId());
        }
    }

    @Test
    public void testConfigureNoErrorIfExists() {
        List<String> configured = new ArrayList<>();

        ConfigureNoErrorIfExistsService s1 = new ConfigureNoErrorIfExistsService(configured);
        boolean processed = processor.process(s1);
        Assertions.assertTrue(processed);
        ConfigureNoErrorIfExistsService s2 = new ConfigureNoErrorIfExistsService(configured);
        processed = processor.process(s2);
        Assertions.assertTrue(processed);

        Assertions.assertEquals(1, configured.size());
        Assertions.assertEquals("/foo", configured.get(0));
    }

    @Service
    public static class ConfigureNoErrorIfExistsService {
        private final List<String> configured;

        public ConfigureNoErrorIfExistsService(List<String> configured) {
            this.configured = configured;
        }

        @Configure(value = "/foo", errorIfExists = false)
        private void configureFooBar(ConfigurableServerChannel channel) {
            configured.add(channel.getId());
        }
    }

    @Test
    public void testConfigureConfigureIfExists() {
        List<String> configured = new ArrayList<>();

        ConfigureConfigureIfExistsService s1 = new ConfigureConfigureIfExistsService(configured);
        boolean processed = processor.process(s1);
        Assertions.assertTrue(processed);
        ConfigureConfigureIfExistsService s2 = new ConfigureConfigureIfExistsService(configured);
        processed = processor.process(s2);
        Assertions.assertTrue(processed);

        Assertions.assertEquals(2, configured.size());
        Assertions.assertEquals("/foo", configured.get(0));
        Assertions.assertEquals("/foo", configured.get(1));
    }

    @Service
    public static class ConfigureConfigureIfExistsService {
        private final List<String> configured;

        public ConfigureConfigureIfExistsService(List<String> configured) {
            this.configured = configured;
        }

        @Configure(value = "/foo", configureIfExists = true)
        private void configureFooBar(ConfigurableServerChannel channel) {
            configured.add(channel.getId());
        }
    }

    @Test
    public void testConfigureOnOverriddenMethod() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        DerivedConfigureOnOverriddenMethodService s = new DerivedConfigureOnOverriddenMethodService(latch);
        boolean processed = processor.process(s);
        Assertions.assertFalse(processed);
        Assertions.assertFalse(latch.await(1, TimeUnit.SECONDS));
    }

    @Service
    public static class ConfigureOnOverriddenMethodService {
        protected final CountDownLatch latch;

        public ConfigureOnOverriddenMethodService(CountDownLatch latch) {
            this.latch = latch;
        }

        @Configure(value = "/foo")
        protected void configureFoo(ConfigurableServerChannel channel) {
            Assertions.assertEquals(1, latch.getCount());
            latch.countDown();
        }
    }

    public static class DerivedConfigureOnOverriddenMethodService extends ConfigureOnOverriddenMethodService {
        public DerivedConfigureOnOverriddenMethodService(CountDownLatch latch) {
            super(latch);
        }

        // Overriding without annotation.
        @Override
        public void configureFoo(ConfigurableServerChannel channel) {
            Assertions.assertEquals(2, latch.getCount());
            latch.countDown();
            super.configureFoo(channel);
        }
    }

    @Test
    public void testConfigureOnOverridingMethod() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        DerivedConfigureOnOverridingMethodService s = new DerivedConfigureOnOverridingMethodService(latch);
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class ConfigureOnOverridingMethodService {
        protected final CountDownLatch latch;

        public ConfigureOnOverridingMethodService(CountDownLatch latch) {
            this.latch = latch;
        }

        @Configure(value = "/foo")
        protected void configureFoo(ConfigurableServerChannel channel) {
            Assertions.assertEquals(1, latch.getCount());
            latch.countDown();
        }
    }

    public static class DerivedConfigureOnOverridingMethodService extends ConfigureOnOverridingMethodService {
        public DerivedConfigureOnOverridingMethodService(CountDownLatch latch) {
            super(latch);
        }

        // Overriding with annotation.
        @Override
        @Configure(value = "/foo")
        public void configureFoo(ConfigurableServerChannel channel) {
            Assertions.assertEquals(2, latch.getCount());
            latch.countDown();
            super.configureFoo(channel);
        }
    }

    @Test
    public void testInjectables() {
        Injectables i = new DerivedInjectables();
        InjectablesService s = new InjectablesService();
        processor = new ServerAnnotationProcessor(bayeuxServer, i);
        boolean processed = processor.process(s);
        Assertions.assertTrue(processed);

        Assertions.assertSame(i, s.i);
    }

    static class Injectables {
    }

    static class DerivedInjectables extends Injectables {
    }

    @Service
    public static class InjectablesService {
        @Inject
        private Injectables i;
    }

    @Service
    private static class S {
        @PostConstruct
        public static void init() {
        }

        @PreDestroy
        public static void destroy() {
        }
    }

    @Test
    public void testListenerWithParameters() throws Exception {
        String value = "test";
        CountDownLatch latch = new CountDownLatch(1);
        Object service = new ListenerWithParametersService(latch, value);
        boolean processed = processor.process(service);
        Assertions.assertTrue(processed);

        String parentChannel = new ChannelId(ListenerWithParametersService.CHANNEL).getParent();

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel(parentChannel + "/" + value);
        message.setData(new HashMap<>());
        message.setClientId(remote.getId());
        process(remote, message);

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class ListenerWithParametersService {
        private static final String CHANNEL = "/foo/{var}";

        private final CountDownLatch latch;
        private final String value;

        public ListenerWithParametersService(CountDownLatch latch, String value) {
            this.latch = latch;
            this.value = value;
        }

        @Listener(CHANNEL)
        public void service(ServerSession session, ServerMessage message, @Param("var") String var) {
            Assertions.assertNotNull(session);
            Assertions.assertNotNull(message);
            Assertions.assertEquals(value, var);
            latch.countDown();
        }
    }

    @Test
    public void testListenerWithParametersNoParamAnnotation() {
        Object service = new ListenerWithoutParamAnnotationService();
        Assertions.assertThrows(IllegalArgumentException.class, () -> processor.process(service));
    }

    @Service
    public static class ListenerWithoutParamAnnotationService {
        @Listener("/foo/{var}")
        public void service(ServerSession session, ServerMessage message, String var) {
        }
    }

    @Test
    public void testListenerWithParametersNotBinding() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Object service = new ListenerWithParametersNotBindingService(latch);
        boolean processed = processor.process(service);
        Assertions.assertTrue(processed);

        String parentChannel = new ChannelId(ListenerWithParametersNotBindingService.CHANNEL).getParent();
        String grandParentChannel = new ChannelId(parentChannel).getParent();

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        // Wrong channel (does not bind to the template), the message must not be delivered.
        message.setChannel(grandParentChannel + "/test");
        message.setData(new HashMap<>());
        message.setClientId(remote.getId());
        process(remote, message);

        Assertions.assertFalse(latch.await(1, TimeUnit.SECONDS));
    }

    @Service
    public static class ListenerWithParametersNotBindingService {
        public static final String CHANNEL = "/a/{b}/c";

        private final CountDownLatch latch;

        public ListenerWithParametersNotBindingService(CountDownLatch latch) {
            this.latch = latch;
        }

        @Listener(CHANNEL)
        public void service(ServerSession session, ServerMessage message, @Param("b") String b) {
            latch.countDown();
        }
    }

    @Test
    public void testListenerWithParameterWithWrongType() {
        Object service = new ListenerWithParametersWithWrongTypeService();
        Assertions.assertThrows(IllegalArgumentException.class, () -> processor.process(service));
    }

    @Service
    public static class ListenerWithParametersWithWrongTypeService {
        public static final String CHANNEL = "/foo/{var}";

        @Listener(CHANNEL)
        public void service(ServerSession session, ServerMessage message, @Param("b") Integer b) {
        }
    }

    @Test
    public void testListenerWithParameterWithoutArgument() {
        Object service = new ListenerWithParameterWithoutArgumentService();
        Assertions.assertThrows(IllegalArgumentException.class, () -> processor.process(service));
    }

    @Service
    public static class ListenerWithParameterWithoutArgumentService {
        public static final String CHANNEL = "/foo/{var}";

        @Listener(CHANNEL)
        public void service(ServerSession session, ServerMessage message) {
        }
    }

    @Test
    public void testListenerWithParameterWrongVariableName() {
        Object service = new ListenerWithParameterWrongVariableNameService();
        Assertions.assertThrows(IllegalArgumentException.class, () -> processor.process(service));
    }

    @Service
    public static class ListenerWithParameterWrongVariableNameService {
        public static final String CHANNEL = "/foo/{var}";

        @Listener(CHANNEL)
        public void service(ServerSession session, ServerMessage message, @Param("wrong") String var) {
        }
    }

    @Test
    public void testListenerWithBadChannelTest() {
        Object service = new BadChannelTestService();
        Assertions.assertThrows(IllegalArgumentException.class, () -> processor.process(service));
    }

    @Service
    public static class BadChannelTestService {
        @Listener("/foo/{var}/*")
        public void bad(ServerSession session, ServerMessage message, @Param("var") String var) {
        }
    }

    @Test
    public void testSubscriptionWithParameters() throws Exception {
        String value = "test";
        CountDownLatch latch = new CountDownLatch(1);
        Object service = new SubscriberWithParametersService(latch, value);
        boolean processed = processor.process(service);
        Assertions.assertTrue(processed);

        String parentChannel = new ChannelId(SubscriberWithParametersService.CHANNEL).getParent();

        // Fake a publish
        LocalSession remote = bayeuxServer.newLocalSession("remote");
        remote.handshake();
        ServerMessage.Mutable message = bayeuxServer.newMessage();
        message.setChannel(parentChannel + "/" + value);
        message.setData(new HashMap<>());
        message.setClientId(remote.getId());
        process(remote, message);

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class SubscriberWithParametersService {
        public static final String CHANNEL = "/foo/{var}";

        private final CountDownLatch latch;
        private final String value;

        public SubscriberWithParametersService(CountDownLatch latch, String value) {
            this.latch = latch;
            this.value = value;
        }

        @Subscription(CHANNEL)
        public void service(Message message, @Param("var") String var) {
            Assertions.assertEquals(value, var);
            latch.countDown();
        }
    }

    @Test
    public void testSubscriptionWithParametersInWrongOrder() {
        Object service = new SubscriptionWithParametersInWrongOrderService();
        Assertions.assertThrows(IllegalArgumentException.class, () -> processor.process(service));
    }

    @Service
    public static class SubscriptionWithParametersInWrongOrderService {
        @Subscription("/a/{b}/{c}")
        public void service(Message message, @Param("c") String c, @Param("b") String b) {
        }
    }

    private void process(LocalSession remote, ServerMessage.Mutable message) throws Exception {
        Promise.Completable<ServerMessage.Mutable> completable = new Promise.Completable<>();
        bayeuxServer.handle((ServerSessionImpl)remote.getServerSession(), message, completable);
        completable.get();
    }
}
