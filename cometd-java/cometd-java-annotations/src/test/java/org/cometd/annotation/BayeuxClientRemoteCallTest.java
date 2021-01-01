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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.JettyJSONContextServer;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.Assert;
import org.junit.Test;

public class BayeuxClientRemoteCallTest extends AbstractClientServerTest {
    @Test
    public void testHTTPRemoteCallWithResult() throws Exception {
        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient));
        testRemoteCallWithResult(client);
    }

    @Test
    public void testHTTPWithAckExtensionRemoteCallWithResult() throws Exception {
        bayeux.addExtension(new AcknowledgedMessagesExtension());
        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(null, httpClient));
        client.addExtension(new AckExtension());
        testRemoteCallWithResult(client);
    }

    private void testRemoteCallWithResult(BayeuxClient client) throws Exception {
        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
        final String response = "response";
        Assert.assertTrue(processor.process(new RemoteCallWithResultService(response)));

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch latch = new CountDownLatch(1);
        client.remoteCall(RemoteCallWithResultService.TARGET, "request", message -> {
            Assert.assertTrue(message.isSuccessful());
            Assert.assertEquals(response, message.getData());
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Service
    public static class RemoteCallWithResultService {
        public static final String TARGET = "/result";
        private final String response;

        public RemoteCallWithResultService(String response) {
            this.response = response;
        }

        @RemoteCall(TARGET)
        public void service(RemoteCall.Caller caller, Object data) {
            caller.result(response);
        }
    }

    @Test
    public void testRemoteCallTimeout() throws Exception {
        long timeout = 1000;

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
        Assert.assertTrue(processor.process(new RemoteCallWithTimeoutService(2 * timeout)));

        BayeuxClient client = newBayeuxClient();
        client.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, timeout);
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch latch = new CountDownLatch(1);
        client.remoteCall(RemoteCallWithTimeoutService.TARGET, "", message -> {
            Assert.assertFalse(message.isSuccessful());
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Service
    public static class RemoteCallWithTimeoutService {
        public static final String TARGET = "/timeout";
        private final long timeout;

        public RemoteCallWithTimeoutService(long timeout) {
            this.timeout = timeout;
        }

        @RemoteCall(TARGET)
        public void service(RemoteCall.Caller caller, Object data) {
            try {
                Thread.sleep(timeout);
                caller.result("ok");
            } catch (Exception x) {
                caller.failure(x.toString());
            }
        }
    }

    @Test
    public void testRemoteCallWithFailure() throws Exception {
        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
        final String failure = "failure";
        Assert.assertTrue(processor.process(new RemoteCallWithFailureService(failure)));

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch latch = new CountDownLatch(1);
        client.remoteCall(RemoteCallWithFailureService.TARGET, "request", message -> {
            Assert.assertFalse(message.isSuccessful());
            Assert.assertEquals(failure, message.getData());
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Service
    public static class RemoteCallWithFailureService {
        public static final String TARGET = "/failure";
        private final String failure;

        public RemoteCallWithFailureService(String failure) {
            this.failure = failure;
        }

        @RemoteCall(TARGET)
        public void service(RemoteCall.Caller caller, Object data) {
            caller.failure(failure);
        }
    }

    @Test
    public void testRemoteCallWithCustomData() throws Exception {
        JettyJSONContextServer jsonContextServer = (JettyJSONContextServer)bayeux.getJSONContext();
        jsonContextServer.getJSON().addConvertor(Custom.class, new CustomConvertor());

        final String request = "request";
        final String response = "response";

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
        boolean processed = processor.process(new RemoteCallWithCustomDataService(request, response));
        Assert.assertTrue(processed);

        Map<String, Object> options = new HashMap<>();
        JettyJSONContextClient jsonContextClient = new JettyJSONContextClient();
        jsonContextClient.getJSON().addConvertor(Custom.class, new CustomConvertor());
        options.put(ClientTransport.JSON_CONTEXT_OPTION, jsonContextClient);
        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(options, httpClient));
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch latch = new CountDownLatch(1);
        client.remoteCall(RemoteCallWithCustomDataService.TARGET, new Custom(request), message -> {
            Assert.assertTrue(message.isSuccessful());
            Custom data = (Custom)message.getData();
            Assert.assertEquals(response, data.payload);
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Service
    public static class RemoteCallWithCustomDataService {
        public static final String TARGET = "/custom_data";

        private String request;
        private String response;

        public RemoteCallWithCustomDataService(String request, String response) {
            this.request = request;
            this.response = response;
        }

        @RemoteCall(TARGET)
        public void service(RemoteCall.Caller caller, Custom custom) {
            if (request.equals(custom.payload)) {
                caller.result(new Custom(response));
            } else {
                caller.failure("failed");
            }
        }
    }

    public static class Custom {
        public final String payload;

        public Custom(String payload) {
            this.payload = payload;
        }
    }

    private class CustomConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object obj, JSON.Output out) {
            Custom custom = (Custom)obj;
            out.addClass(custom.getClass());
            out.add("payload", custom.payload);
        }

        @Override
        @SuppressWarnings("rawtypes")
        public Object fromJSON(Map object) {
            return new Custom((String)object.get("payload"));
        }
    }

    @Test
    public void testRemoteCallWithAsyncResult() throws Exception {
        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
        final String response = "response";
        Assert.assertTrue(processor.process(new RemoteCallWithAsyncResultService(response)));

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        final CountDownLatch latch = new CountDownLatch(1);
        client.remoteCall(RemoteCallWithResultService.TARGET, "request", message -> {
            Assert.assertTrue(message.isSuccessful());
            Assert.assertEquals(response, message.getData());
            latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Service
    public static class RemoteCallWithAsyncResultService {
        public static final String TARGET = "/result";
        private final String response;

        public RemoteCallWithAsyncResultService(String response) {
            this.response = response;
        }

        @RemoteCall(TARGET)
        public void service(final RemoteCall.Caller caller, Object data) {
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    caller.result(response);
                } catch (Throwable x) {
                    caller.failure(x.toString());
                }
            }).start();
        }
    }
}
