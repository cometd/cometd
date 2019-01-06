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
package org.cometd.examples.spring.boot;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;
import org.eclipse.jetty.client.HttpClient;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CometDApplicationTest {
    @LocalServerPort
    public int serverPort;
    private HttpClient httpClient;

    @Before
    public void prepare() throws Exception {
        httpClient = new HttpClient();
        httpClient.start();
    }

    @After
    public void dispose() throws Exception {
        if (httpClient != null) {
            httpClient.stop();
        }
    }

    @Test
    public void testSpringBoot() throws Exception {
        String url = "http://localhost:" + serverPort + "/cometd";
        BayeuxClient client = new BayeuxClient(url, new LongPollingTransport(null, httpClient));

        CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.handshake(message -> {
            if (message.isSuccessful()) {
                handshakeLatch.countDown();
            }
        });
        Assert.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch messageLatch = new CountDownLatch(1);
        String data = "yo-ohh!";
        client.remoteCall("echo", data, message -> {
            if (message.isSuccessful()) {
                Assert.assertEquals(data, message.getData());
                messageLatch.countDown();
            }
        });
        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch disconnectLatch = new CountDownLatch(1);
        client.disconnect(message -> disconnectLatch.countDown());
        Assert.assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));
    }
}
