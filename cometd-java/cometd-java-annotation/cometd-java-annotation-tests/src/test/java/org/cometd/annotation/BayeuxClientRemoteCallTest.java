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
import org.cometd.annotation.server.RemoteCall;
import org.cometd.annotation.server.ServerAnnotationProcessor;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.JettyJSONContextServer;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BayeuxClientRemoteCallTest extends AbstractClientServerTest {
    @Test
    public void testHTTPRemoteCallWithResult() throws Exception {
        BayeuxClient client = newBayeuxClient();
        testRemoteCallWithResult(client);
    }

    @Test
    public void testHTTPWithAckExtensionRemoteCallWithResult() throws Exception {
        bayeux.addExtension(new AcknowledgedMessagesExtension());
        BayeuxClient client = newBayeuxClient();
        client.addExtension(new AckExtension());
        testRemoteCallWithResult(client);
    }

    private void testRemoteCallWithResult(BayeuxClient client) throws Exception {
        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
        String response = "response";
        Assertions.assertTrue(processor.process(new RemoteCallWithResultService(response)));

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        CountDownLatch latch = new CountDownLatch(1);
        client.remoteCall(RemoteCallWithResultService.TARGET, "request", message -> {
            Assertions.assertTrue(message.isSuccessful());
            Assertions.assertEquals(response, message.getData());
            latch.countDown();
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

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
        Assertions.assertTrue(processor.process(new RemoteCallWithTimeoutService(2 * timeout)));

        BayeuxClient client = newBayeuxClient();
        client.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, timeout);
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        CountDownLatch latch = new CountDownLatch(1);
        client.remoteCall(RemoteCallWithTimeoutService.TARGET, "", message -> {
            Assertions.assertFalse(message.isSuccessful());
            latch.countDown();
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

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
        String failure = "failure";
        Assertions.assertTrue(processor.process(new RemoteCallWithFailureService(failure)));

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        CountDownLatch latch = new CountDownLatch(1);
        client.remoteCall(RemoteCallWithFailureService.TARGET, "request", message -> {
            Assertions.assertFalse(message.isSuccessful());
            Assertions.assertEquals(failure, message.getData());
            latch.countDown();
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

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

        String request = "request";
        String response = "response";

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
        boolean processed = processor.process(new RemoteCallWithCustomDataService(request, response));
        Assertions.assertTrue(processed);

        Map<String, Object> options = new HashMap<>();
        JettyJSONContextClient jsonContextClient = new JettyJSONContextClient();
        jsonContextClient.getJSON().addConvertor(Custom.class, new CustomConvertor());
        options.put(ClientTransport.JSON_CONTEXT_OPTION, jsonContextClient);
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(options, httpClient));
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        CountDownLatch latch = new CountDownLatch(1);
        client.remoteCall(RemoteCallWithCustomDataService.TARGET, new Custom(request), message -> {
            Assertions.assertTrue(message.isSuccessful());
            Custom data = (Custom)message.getData();
            Assertions.assertEquals(response, data.payload);
            latch.countDown();
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Service
    public static class RemoteCallWithCustomDataService {
        public static final String TARGET = "/custom_data";

        private final String request;
        private final String response;

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

    private static class CustomConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object obj, JSON.Output out) {
            Custom custom = (Custom)obj;
            out.addClass(custom.getClass());
            out.add("payload", custom.payload);
        }

        @Override
        public Object fromJSON(Map<String, Object> object) {
            return new Custom((String)object.get("payload"));
        }
    }

    @Test
    public void testRemoteCallWithAsyncResult() throws Exception {
        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
        String response = "response";
        Assertions.assertTrue(processor.process(new RemoteCallWithAsyncResultService(response)));

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        CountDownLatch latch = new CountDownLatch(1);
        client.remoteCall(RemoteCallWithResultService.TARGET, "request", message -> {
            Assertions.assertTrue(message.isSuccessful());
            Assertions.assertEquals(response, message.getData());
            latch.countDown();
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

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
        public void service(RemoteCall.Caller caller, Object data) {
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
