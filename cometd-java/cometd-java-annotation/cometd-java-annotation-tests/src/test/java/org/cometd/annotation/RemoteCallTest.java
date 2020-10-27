/*
 * Copyright (c) 2008-2020 the original author or authors.
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.annotation.server.RemoteCall;
import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.server.BayeuxServerImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RemoteCallTest {
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
    public void testRemoteCallWithResult() throws Exception {
        Object callerData = "callerData";
        Object calleeData = "calleeData";

        Object service = new RemoteCallWithResultService(callerData, calleeData);
        boolean processed = processor.process(service);
        Assertions.assertTrue(processed);

        LocalSession remote = bayeuxServer.newLocalSession("remoteCall");
        remote.handshake();
        ClientSessionChannel channel = remote.getChannel(Channel.SERVICE + RemoteCallWithResultService.CHANNEL);
        CountDownLatch latch = new CountDownLatch(1);
        channel.addListener((ClientSessionChannel.MessageListener)(c, m) -> {
            if (m.isPublishReply()) {
                return;
            }
            Assertions.assertTrue(m.isSuccessful());
            Assertions.assertEquals(calleeData, m.getData());
            latch.countDown();
        });
        channel.publish(callerData);

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class RemoteCallWithResultService {
        public static final String CHANNEL = "/target";

        private final Object callerData;
        private final Object calleeData;

        public RemoteCallWithResultService(Object callerData, Object calleeData) {
            this.callerData = callerData;
            this.calleeData = calleeData;
        }

        @RemoteCall(CHANNEL)
        public void service(RemoteCall.Caller caller, Object data) {
            if (!callerData.equals(data)) {
                caller.failure("Invalid data from caller: " + data);
                return;
            }

            new Thread(() -> caller.result(calleeData)).start();
        }
    }

    @Test
    public void testRemoteCallWithParametersWithResult() throws Exception {
        Object service = new RemoteCallWithParametersWithResultService();
        boolean processed = processor.process(service);
        Assertions.assertTrue(processed);

        LocalSession remote = bayeuxServer.newLocalSession("remoteCall");
        remote.handshake();

        String parameter = "param1";
        ClientSessionChannel channel = remote.getChannel(Channel.SERVICE + "/test/" + parameter);
        CountDownLatch latch = new CountDownLatch(1);
        channel.addListener((ClientSessionChannel.MessageListener)(c, m) -> {
            if (m.isPublishReply()) {
                return;
            }
            Assertions.assertEquals(parameter, m.getData());
            latch.countDown();
        });
        channel.publish(new HashMap<>());

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class RemoteCallWithParametersWithResultService {
        @RemoteCall("/test/{p}")
        public void serviceWithParameter(RemoteCall.Caller caller, Object data, @Param("p") String param) {
            caller.result(param);
        }
    }

    @Test
    public void testTwoRemoteCallsWithResult() throws Exception {
        Object service = new TwoRemoteCallsWithResultService();
        boolean processed = processor.process(service);
        Assertions.assertTrue(processed);

        LocalSession remote1 = bayeuxServer.newLocalSession("remoteCall1");
        remote1.handshake();

        LocalSession remote2 = bayeuxServer.newLocalSession("remoteCall2");
        remote2.handshake();

        CountDownLatch latch = new CountDownLatch(2);
        List<Message> responses = new ArrayList<>();

        ClientSessionChannel channel1 = remote1.getChannel(Channel.SERVICE + TwoRemoteCallsWithResultService.CHANNEL);
        channel1.addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isPublishReply()) {
                return;
            }
            responses.add(message);
            latch.countDown();
        });

        ClientSessionChannel channel2 = remote2.getChannel(Channel.SERVICE + TwoRemoteCallsWithResultService.CHANNEL);
        channel2.addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isPublishReply()) {
                return;
            }
            responses.add(message);
            latch.countDown();
        });

        channel1.publish("1");
        channel2.publish("2");

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        Assertions.assertEquals(2, responses.size());
        Message response1 = responses.get(0);
        Assertions.assertEquals("1", response1.getData());
        Message response2 = responses.get(1);
        Assertions.assertEquals("2", response2.getData());
    }

    @Service
    public static class TwoRemoteCallsWithResultService {
        public static final String CHANNEL = "/two_remote";

        @RemoteCall(CHANNEL)
        public void service(RemoteCall.Caller caller, Object data) {
            caller.result(data);
        }
    }

    @Test
    public void testRemoteCallWithFailure() throws Exception {
        String failure = "failure";

        Object service = new RemoteCallWithFailureService();
        boolean processed = processor.process(service);
        Assertions.assertTrue(processed);

        LocalSession remote = bayeuxServer.newLocalSession("remoteCall");
        remote.handshake();
        ClientSessionChannel channel = remote.getChannel(Channel.SERVICE + RemoteCallWithFailureService.CHANNEL);
        CountDownLatch latch = new CountDownLatch(1);
        channel.addListener((ClientSessionChannel.MessageListener)(c, m) -> {
            if (m.isPublishReply()) {
                return;
            }
            Assertions.assertFalse(m.isSuccessful());
            Assertions.assertEquals(failure, m.getData());
            latch.countDown();
        });
        channel.publish(failure);

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class RemoteCallWithFailureService {
        public static final String CHANNEL = "/call_failure";

        @RemoteCall(CHANNEL)
        public void service(RemoteCall.Caller caller, String data) {
            caller.failure(data);
        }
    }

    @Test
    public void testRemoteCallWithUncaughtException() throws Exception {
        Object service = new RemoteCallWithUncaughtExceptionService();
        boolean processed = processor.process(service);
        Assertions.assertTrue(processed);

        LocalSession remote = bayeuxServer.newLocalSession("remoteCall");
        remote.handshake();
        ClientSessionChannel channel = remote.getChannel(Channel.SERVICE + RemoteCallWithUncaughtExceptionService.CHANNEL);
        CountDownLatch latch = new CountDownLatch(1);
        channel.addListener((ClientSessionChannel.MessageListener)(c, m) -> {
            if (m.isPublishReply()) {
                return;
            }
            Assertions.assertFalse(m.isSuccessful());
            Assertions.assertNotNull(m.getData());
            latch.countDown();
        });
        channel.publish("throw");

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Service
    public static class RemoteCallWithUncaughtExceptionService {
        public static final String CHANNEL = "/uncaught";

        @RemoteCall(CHANNEL)
        public void service(RemoteCall.Caller caller, Object data) {
            throw new RuntimeException("explicitly thrown by test");
        }
    }
}
