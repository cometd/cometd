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
package org.cometd.websocket;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.HandshakeResponse;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.HandshakeRequest;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.websocket.client.JettyWebSocketTransport;
import org.cometd.websocket.client.WebSocketTransport;
import org.cometd.websocket.server.common.AbstractWebSocketTransport;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class ExtensionNegotiationTest extends ClientServerWebSocketTest {
    private final Map<String, List<String>> responseHeaders = new HashMap<>();

    public ExtensionNegotiationTest(String wsTransportType) {
        super(wsTransportType);
    }

    @Override
    protected WebSocketTransport newWebSocketTransport(String url, Map<String, Object> options, WebSocketContainer wsContainer) {
        return new WebSocketTransport(url, options, null, wsContainer) {
            @Override
            protected void onHandshakeRequest(Map<String, List<String>> headers) {
                super.onHandshakeRequest(headers);
                headers.put(HandshakeRequest.SEC_WEBSOCKET_EXTENSIONS, Arrays.asList("identity", "fragment"));
            }

            @Override
            protected void onHandshakeResponse(HandshakeResponse response) {
                super.onHandshakeResponse(response);
                responseHeaders.putAll(response.getHeaders());
            }
        };
    }

    @Override
    protected JettyWebSocketTransport newJettyWebSocketTransport(String url, Map<String, Object> options, WebSocketClient wsClient) {
        return new JettyWebSocketTransport(url, options, null, wsClient) {
            @Override
            public void onHandshakeRequest(UpgradeRequest request) {
                super.onHandshakeRequest(request);
                request.addExtensions("identity", "fragment");
            }

            @Override
            public void onHandshakeResponse(UpgradeResponse response) {
                super.onHandshakeResponse(response);
                responseHeaders.putAll(response.getHeaders());
            }
        };
    }

    @Test
    public void testExtensionNegotiation() throws Exception {
        // Jetty 9.2 JSR client does not support extensions.
        Assume.assumeThat(wsTransportType, Matchers.equalTo(WEBSOCKET_JETTY));

        // Disable the identity extension on server.
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put(AbstractWebSocketTransport.ENABLE_EXTENSION_PREFIX_OPTION + "identity", "false");
        prepareAndStart(serverOptions);

        // Extension identity is enabled by default on the client.
        BayeuxClient client = newBayeuxClient();
        final CountDownLatch latch = new CountDownLatch(1);
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (message.isSuccessful()) {
                    List<String> extensions = responseHeaders.get(HandshakeRequest.SEC_WEBSOCKET_EXTENSIONS);
                    boolean hasFragment = false;
                    for (String extension : extensions) {
                        if (extension.contains("identity")) {
                            Assert.fail();
                        }
                        if (extension.contains("fragment")) {
                            hasFragment = true;
                        }
                    }
                    Assert.assertTrue(hasFragment);
                    latch.countDown();
                }
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        disconnectBayeuxClient(client);
    }
}
