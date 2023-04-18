/*
 * Copyright (c) 2008-2022 the original author or authors.
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
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;

import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.websocket.jakarta.WebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Disabled("There is no support for Jetty 12 in Spring Boot yet")
public class CometDApplicationTest {
    @LocalServerPort
    public int serverPort;
    private HttpClient httpClient;
    private WebSocketContainer wsContainer;

    @BeforeEach
    public void prepare() throws Exception {
        httpClient = new HttpClient();
        wsContainer = ContainerProvider.getWebSocketContainer();
        httpClient.addBean(wsContainer);
        httpClient.start();
    }

    @AfterEach
    public void dispose() throws Exception {
        if (httpClient != null) {
            httpClient.stop();
        }
    }

    @Test
    public void testSpringBootWithHTTPTransport() throws Exception {
        testSpringBoot(new JettyHttpClientTransport(null, httpClient));
    }

    @Test
    public void testSpringBootWithWebSocketTransport() throws Exception {
        testSpringBoot(new WebSocketTransport(null, null, wsContainer));
    }

    private void testSpringBoot(ClientTransport transport) throws Exception {
        String url = "http://localhost:" + serverPort + "/cometd";
        BayeuxClient client = new BayeuxClient(url, transport);

        CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.handshake(message -> {
            if (message.isSuccessful()) {
                handshakeLatch.countDown();
            }
        });
        Assertions.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch messageLatch = new CountDownLatch(1);
        String data = "yo-ohh!";
        client.remoteCall("echo", data, message -> {
            if (message.isSuccessful()) {
                Assertions.assertEquals(data, message.getData());
                messageLatch.countDown();
            }
        });
        Assertions.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch disconnectLatch = new CountDownLatch(1);
        client.disconnect(message -> disconnectLatch.countDown());
        Assertions.assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS));
    }
}
