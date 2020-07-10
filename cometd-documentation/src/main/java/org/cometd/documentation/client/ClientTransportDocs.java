/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.documentation.client;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import okhttp3.OkHttpClient;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.http.okhttp.OkHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.client.websocket.okhttp.OkHttpWebSocketTransport;
import org.eclipse.jetty.client.HttpClient;

@SuppressWarnings("unused")
public class ClientTransportDocs {
    public void transports() throws Exception {
        // tag::transports[]
        // Prepare the JSR 356 WebSocket transport.
        // Create a JSR 356 WebSocketContainer using the standard APIs.
        WebSocketContainer webSocketContainer = ContainerProvider.getWebSocketContainer();
        // Create the CometD JSR 356 websocket transport.
        ClientTransport wsTransport = new WebSocketTransport(null, null, webSocketContainer);

        // Prepare the HTTP transport.
        // Create a Jetty HttpClient instance.
        HttpClient httpClient = new HttpClient();
        // Add the webSocketContainer as a dependent bean of HttpClient.
        // If webSocketContainer is the Jetty implementation, then
        // webSocketContainer will follow the lifecycle of HttpClient.
        httpClient.addBean(webSocketContainer, true);
        httpClient.start();
        // Create the CometD long-polling transport.
        ClientTransport httpTransport = new JettyHttpClientTransport(null, httpClient);

        // Configure BayeuxClient, with the websocket transport listed before the long-polling transport.
        BayeuxClient client = new BayeuxClient("http://localhost:8080/cometd", wsTransport, httpTransport);

        // Handshake with the server.
        client.handshake();
        // end::transports[]
    }

    public void okhttp() throws Exception {
        // tag::okhttp[]
        // Prepare the OkHttp WebSocket transport.
        // Create an OkHttp instance.
        OkHttpClient okHttpClient = new OkHttpClient();
        // Create the CometD OkHttp websocket transport.
        ClientTransport wsTransport = new OkHttpWebSocketTransport(null, okHttpClient);

        // Prepare the OkHttp HTTP transport.
        // Create the CometD long-polling transport.
        ClientTransport httpTransport = new OkHttpClientTransport(null, okHttpClient);

        // Configure BayeuxClient, with the websocket transport listed before the long-polling transport.
        BayeuxClient client = new BayeuxClient("http://localhost:8080/cometd", wsTransport, httpTransport);

        // Handshake with the server.
        client.handshake();
        // end::okhttp[]
    }
}
