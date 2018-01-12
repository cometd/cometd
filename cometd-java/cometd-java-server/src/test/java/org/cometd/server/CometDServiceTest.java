/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CometDServiceTest extends AbstractBayeuxClientServerTest {
    public CometDServiceTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception {
        startServer(null);
    }

    @Test
    public void testRemoveService() throws Exception {
        final String channel1 = "/foo";
        final String channel2 = "/bar";
        final AtomicReference<CountDownLatch> publishLatch1 = new AtomicReference<>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> publishLatch2 = new AtomicReference<>(new CountDownLatch(1));
        AbstractService service = new OneTwoService(bayeux, channel1, channel2, publishLatch1, publishLatch2);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request publish1 = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel1 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish1.send();
        Assert.assertTrue(publishLatch1.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.getStatus());

        Request publish2 = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel2 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish2.send();
        Assert.assertTrue(publishLatch2.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.getStatus());

        service.removeService(channel1, "one");
        publishLatch1.set(new CountDownLatch(1));
        publishLatch2.set(new CountDownLatch(1));

        publish1 = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel1 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish1.send();
        Assert.assertFalse(publishLatch1.get().await(1, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.getStatus());

        publish2 = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel2 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish2.send();
        Assert.assertTrue(publishLatch2.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.getStatus());

        service.removeService(channel2);
        publishLatch2.set(new CountDownLatch(1));

        publish2 = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel2 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish2.send();
        Assert.assertFalse(publishLatch2.get().await(1, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.getStatus());

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assert.assertEquals(200, response.getStatus());
    }

    public static class OneTwoService extends AbstractService {
        private final AtomicReference<CountDownLatch> publishLatch1;
        private final AtomicReference<CountDownLatch> publishLatch2;

        public OneTwoService(BayeuxServerImpl bayeux, String channel1, String channel2, AtomicReference<CountDownLatch> publishLatch1, AtomicReference<CountDownLatch> publishLatch2) {
            super(bayeux, "test_remove");
            this.publishLatch1 = publishLatch1;
            this.publishLatch2 = publishLatch2;
            addService(channel1, "one");
            addService(channel2, "two");
        }

        public void one(ServerSession remote, ServerMessage message) {
            publishLatch1.get().countDown();
        }

        public void two(ServerSession remote, ServerMessage message) {
            publishLatch2.get().countDown();
        }
    }
}
