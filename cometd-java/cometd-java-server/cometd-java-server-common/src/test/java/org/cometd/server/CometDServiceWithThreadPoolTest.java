/*
 * Copyright (c) 2008-2019 the original author or authors.
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
package org.cometd.server;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CometDServiceWithThreadPoolTest extends AbstractBayeuxClientServerTest {
    public CometDServiceWithThreadPoolTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception {
        startServer(null);
    }

    @Test
    public void testBayeuxServiceWithThreadPool() throws Exception {
        final String channel = "/foo";

        TestService service = new TestService(bayeux, channel);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = subscribe.send();
        Assert.assertEquals(200, response.getStatus());

        Request publish = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send();
        Assert.assertEquals(200, response.getStatus());

        Assert.assertTrue(service.await(5000));

        Message message = service.getMessage();
        Assert.assertNotNull(message);
        Assert.assertNotNull(message.getChannel());
        Assert.assertNotNull(message.getData());
    }

    public static class TestService extends AbstractService {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Message message;

        public TestService(BayeuxServerImpl bayeux, String channel) {
            super(bayeux, "test", 5);
            addService(channel, "handle");
        }

        @Override
        protected void doInvoke(Method method, ServerSession session, ServerMessage message) {
            // Sleep for a while to simulate a slow dispatch
            sleep(500);
            // Save a copy of the message to test it later
            this.message = message;
            super.doInvoke(method, session, message);
            latch.countDown();
        }

        private void sleep(long time) {
            try {
                Thread.sleep(time);
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
            }
        }

        public void handle(ServerSession remote, ServerMessage message) {
        }

        public boolean await(long time) throws InterruptedException {
            return latch.await(time, TimeUnit.MILLISECONDS);
        }

        public Message getMessage() {
            return message;
        }
    }
}
