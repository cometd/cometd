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
package org.cometd.tests;

import java.util.Map;
import java.util.Objects;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.cometd.annotation.Service;
import org.cometd.annotation.server.RemoteCall;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.JettyJSONContextServer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.websocket.client.WebSocketClient;

@Service
public class WebAppService {
    public static final String HTTP_CHANNEL = "/echo_http";
    public static final String JETTY_WS_CHANNEL = "/echo_jetty_ws";
    public static final String JAVAX_WS_CHANNEL = "/echo_javax_ws";
    public static final String JAVAX_WS_CUSTOM_CHANNEL = "/echo_javax_ws_custom";
    private static final String SERVICE_CHANNEL = "/service/echo";
    private HttpClient httpClient;
    private WebSocketClient wsClient;
    private WebSocketContainer wsContainer;

    @PostConstruct
    public void init() throws Exception {
        httpClient = new HttpClient();
        wsContainer = ContainerProvider.getWebSocketContainer();
        // Add the container as a bean so that it can be stopped.
        httpClient.addBean(wsContainer);
        httpClient.start();
        // Cannot pass the HttpClient to the constructor because I will get a
        // LinkageError, as WebSocketClient is loaded from the server classpath.
        // Also, I must start it independently from HttpClient because
        // WebSocketClient implements LifeCycle from the server classpath,
        // not the LifeCycle from the webapp classpath like HttpClient does.
        wsClient = new WebSocketClient();
        wsClient.start();
    }

    @PreDestroy
    public void destroy() throws Exception {
        wsClient.stop();
        httpClient.stop();
    }

    @RemoteCall(HTTP_CHANNEL)
    public void invokeViaHTTP(RemoteCall.Caller caller, Object data) {
        invokeService(new JettyHttpClientTransport(null, httpClient), caller, data);
    }

    @RemoteCall(JETTY_WS_CHANNEL)
    public void invokeViaJettyWebSocket(RemoteCall.Caller caller, Object data) {
        invokeService(new JettyWebSocketTransport(null, null, wsClient), caller, data);
    }

    @RemoteCall(JAVAX_WS_CHANNEL)
    public void invokeViaJavaxWebSocket(RemoteCall.Caller caller, Object data) {
        invokeService(new WebSocketTransport(null, null, wsContainer), caller, data);
    }

    @RemoteCall(JAVAX_WS_CUSTOM_CHANNEL)
    public void invokeViaJavaxWebSocketCustom(RemoteCall.Caller caller, Custom custom) {
        invokeServiceCustom(new WebSocketTransport(null, null, wsContainer), caller, custom);
    }

    private void invokeService(ClientTransport transport, RemoteCall.Caller caller, Object data) {
        String uri = (String)data;
        BayeuxClient client = new BayeuxClient(uri, transport);
        client.handshake(hs -> {
            if (hs.isSuccessful()) {
                client.remoteCall(SERVICE_CHANNEL, data, response -> {
                    if (response.isSuccessful()) {
                        caller.result(response.getData());
                    } else {
                        caller.failure("Could not invoke echo service: " + response);
                    }
                    client.disconnect();
                });
            } else {
                caller.failure("Could not handshake: " + hs);
                client.disconnect();
            }
        });
    }

    private void invokeServiceCustom(ClientTransport transport, RemoteCall.Caller caller, Object data) {
        String uri = ((Custom)data).data;
        BayeuxClient client = new BayeuxClient(uri, transport);
        client.setOption("jsonContext", new JSONClientConfig());
        client.handshake(hs -> {
            if (hs.isSuccessful()) {
                client.remoteCall(SERVICE_CHANNEL, data, response -> {
                    if (response.isSuccessful()) {
                        Object responseData = response.getData();
                        if (responseData instanceof Custom) {
                            caller.result(responseData);
                        } else {
                            caller.failure("Custom deserialization failed: " + response);
                        }
                    } else {
                        caller.failure("Could not invoke echo service: " + response);
                    }
                    client.disconnect();
                });
            } else {
                caller.failure("Could not handshake: " + hs);
                client.disconnect();
            }
        });
    }

    @RemoteCall(SERVICE_CHANNEL)
    public void echo(RemoteCall.Caller caller, Object data) {
        caller.result(data);
    }

    public static class JSONClientConfig extends JettyJSONContextClient {
        public JSONClientConfig() {
            putConvertor(WebAppService.Custom.class.getName(), new WebAppService.CustomConvertor());
        }
    }

    public static class JSONServerConfig extends JettyJSONContextServer {
        public JSONServerConfig() {
            putConvertor(Custom.class.getName(), new CustomConvertor());
        }
    }

    public static class Custom {
        String data;

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            Custom that = (Custom)obj;
            return Objects.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return Objects.hash(data);
        }
    }

    public static final class CustomConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object obj, JSON.Output out) {
            Custom custom = (Custom)obj;
            out.addClass(custom.getClass());
            out.add("data", custom.data);
        }

        @Override
        public Object fromJSON(Map map) {
            Custom custom = new Custom();
            custom.data = (String)map.get("data");
            return custom;
        }
    }
}
