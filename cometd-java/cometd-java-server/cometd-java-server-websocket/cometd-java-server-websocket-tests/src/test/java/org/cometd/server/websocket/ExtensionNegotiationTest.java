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
package org.cometd.server.websocket;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.HandshakeResponse;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.HandshakeRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.cometd.client.websocket.okhttp.OkHttpWebSocketTransport;
import org.cometd.server.websocket.common.AbstractWebSocketTransport;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ExtensionNegotiationTest extends ClientServerWebSocketTest {
    private final Map<String, List<String>> responseHeaders = new HashMap<>();

    @Override
    protected ClientTransport newWebSocketTransport(String url, Map<String, Object> options, WebSocketContainer wsContainer) {
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
    protected ClientTransport newJettyWebSocketTransport(String url, Map<String, Object> options, WebSocketClient wsClient) {
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

    @Override
    protected ClientTransport newOkHttpWebSocketTransport(String url, Map<String, Object> options, OkHttpClient okHttpClient) {
        return new OkHttpWebSocketTransport(url, options, null, okHttpClient) {
            @Override
            protected void onHandshakeRequest(String uri, Request.Builder upgradeRequest) {
                super.onHandshakeRequest(uri, upgradeRequest);
                upgradeRequest.header(HandshakeRequest.SEC_WEBSOCKET_EXTENSIONS, "identity, fragment");
            }

            @Override
            protected void onHandshakeResponse(Response response) {
                super.onHandshakeResponse(response);
                responseHeaders.putAll(OkHttpWebSocketTransport.headersToMap(response.headers()));
            }
        };
    }

    @ParameterizedTest
    @MethodSource("wsTypes")
    public void testExtensionNegotiation(String wsType) throws Exception {
        // OkHttp does not support specifying WebSocket extensions.
        Assumptions.assumeFalse(wsType.equals(WEBSOCKET_OKHTTP));

        // Disable the identity extension on server.
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put(AbstractWebSocketTransport.ENABLE_EXTENSION_PREFIX_OPTION + "identity", "false");
        prepareAndStart(wsType, serverOptions);

        // Extension identity is enabled by default on the client.
        BayeuxClient client = newBayeuxClient(wsType);
        CountDownLatch latch = new CountDownLatch(1);
        client.handshake(message -> {
            if (message.isSuccessful()) {
                List<String> extensions = responseHeaders.get(HandshakeRequest.SEC_WEBSOCKET_EXTENSIONS);
                boolean hasFragment = false;
                for (String extension : extensions) {
                    if (extension.contains("identity")) {
                        Assertions.fail();
                    }
                    if (extension.contains("fragment")) {
                        hasFragment = true;
                    }
                }
                Assertions.assertTrue(hasFragment);
                latch.countDown();
            }
        });

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
