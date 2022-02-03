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
package org.cometd.demo;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DemoTest {
    @Test
    public void testDemo() throws Exception {
        int httpPort = newServerPort();
        int httpsPort = newServerPort();
        String contextPath = "/ctx";

        // Start the Server.
        Server server = new Demo(httpPort, httpsPort, contextPath).start();

        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStoreType("pkcs12");
        sslContextFactory.setKeyStorePassword("storepwd");

        // Starts the HTTP client.
        HttpClient httpClient = new HttpClient(sslContextFactory);
        httpClient.start();

        // Starts the WebSocket client.
        // NOTE: due to limitations of the JSR specification, this client only works over clear-text.
        WebSocketContainer jsrWebSocketClient = ContainerProvider.getWebSocketContainer();

        // Jetty's WebSocket client works also over TLS.
        WebSocketClient jettyWebSocketClient = new WebSocketClient(httpClient);
        jettyWebSocketClient.start();

        try {
            // Test clear-text communication.
            String clearTextURL = "http://localhost:" + httpPort + contextPath + "/cometd";
            BayeuxClient client = new BayeuxClient(clearTextURL, new WebSocketTransport(null, null, jsrWebSocketClient));
            client.handshake();
            Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

            client.disconnect();
            Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));

            // Test confidential communication.
            String confidentialURL = "https://localhost:" + httpsPort + contextPath + "/cometd";
            client = new BayeuxClient(confidentialURL, new JettyHttpClientTransport(null, httpClient));
            client.handshake();
            Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

            client.disconnect();
            Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));

            client = new BayeuxClient(confidentialURL, new JettyWebSocketTransport(null, null, jettyWebSocketClient));
            client.handshake();
            Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

            client.disconnect();
            Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));

            // Test JMX.
            MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
            Set<ObjectInstance> objectInstances = mbeanServer.queryMBeans(ObjectName.getInstance("org.cometd.server:*"), null);
            Assertions.assertFalse(objectInstances.isEmpty());
        } finally {
            jettyWebSocketClient.stop();
            if (jsrWebSocketClient instanceof LifeCycle) {
                ((LifeCycle)jsrWebSocketClient).stop();
            }
            httpClient.stop();
            server.stop();
        }
    }

    private int newServerPort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
}
