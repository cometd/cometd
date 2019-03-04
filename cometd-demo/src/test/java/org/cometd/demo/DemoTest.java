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
package org.cometd.demo;

import java.lang.management.ManagementFactory;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.websocket.client.JettyWebSocketTransport;
import org.cometd.websocket.client.WebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.Assert;
import org.junit.Test;

public class DemoTest {
    @Test
    public void testDemo() throws Exception {
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");

        // Start the Server.
        Server server = Demo.start();

        // Starts the HTTP client.
        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.start();

        // Starts the WebSocket client.
        // NOTE: due to limitations of the JSR specification, this client only works over clear-text.
        WebSocketContainer jsrWebSocketClient = ContainerProvider.getWebSocketContainer();

        // Jetty's WebSocket client works also over TLS.
        WebSocketClient jettyWebSocketClient = new WebSocketClient(sslContextFactory);
        jettyWebSocketClient.start();

        try {
            // Test clear-text communication.
            String clearTextURL = "http://localhost:" + Demo.HTTP_PORT + Demo.CONTEXT_PATH + "/cometd";
            BayeuxClient client = new BayeuxClient(clearTextURL, new WebSocketTransport(null, null, jsrWebSocketClient));
            client.handshake();
            Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

            client.disconnect();
            Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));

            // Test confidential communication.
            String confidentialURL = "https://localhost:" + Demo.HTTPS_PORT + Demo.CONTEXT_PATH + "/cometd";
            client = new BayeuxClient(confidentialURL, new LongPollingTransport(null, httpClient));
            client.handshake();
            Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

            client.disconnect();
            Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));

            client = new BayeuxClient(confidentialURL, new JettyWebSocketTransport(null, null, jettyWebSocketClient));
            client.handshake();
            Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

            client.disconnect();
            Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));

            // Test JMX.
            MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
            Set<ObjectInstance> objectInstances = mbeanServer.queryMBeans(ObjectName.getInstance("org.cometd.server:*"), null);
            Assert.assertFalse(objectInstances.isEmpty());
        } finally {
            jettyWebSocketClient.stop();
            if (jsrWebSocketClient instanceof LifeCycle) {
                ((LifeCycle)jsrWebSocketClient).stop();
            }
            httpClient.stop();
            server.stop();
        }
    }
}
