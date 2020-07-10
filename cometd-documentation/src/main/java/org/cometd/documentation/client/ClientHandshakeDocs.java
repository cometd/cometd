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

import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.client.ClientSessionChannel.MessageListener;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.eclipse.jetty.client.HttpClient;

@SuppressWarnings("unused")
public class ClientHandshakeDocs {
    public void handshakeSetup() throws Exception {
        // tag::handshakeSetup[]
        // Create (and eventually configure) Jetty's HttpClient.
        HttpClient httpClient = new HttpClient();
        // Here configure Jetty's HttpClient.
        // httpClient.setMaxConnectionsPerDestination(2);
        httpClient.start();

        // Prepare the transport.
        Map<String, Object> options = new HashMap<>();
        ClientTransport transport = new JettyHttpClientTransport(options, httpClient);

        // Create the BayeuxClient.
        ClientSession bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);

        // Here prepare the BayeuxClient, for example:
        // Add the message acknowledgement extension.
        bayeuxClient.addExtension(new AckExtension());
        // Register a listener for channel /service/business.
        ClientSessionChannel channel = bayeuxClient.getChannel("/service/business");
        channel.addListener((MessageListener)(c, message) -> {
            System.err.printf("Received message on %s: %s%n", c, message);
        });

        // Handshake with the server.
        bayeuxClient.handshake();
        // end::handshakeSetup[]
    }

    public void callbackHandshake() throws Exception {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        // tag::callbackHandshake[]
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        bayeuxClient.handshake(handshakeReply -> {
            if (handshakeReply.isSuccessful()) {
                // Here the handshake with the server is successful.
            }
        });
        // end::callbackHandshake[]
    }

    public void listenerHandshake() throws Exception {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        // tag::listenerHandshake[]
        ClientSession bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        ClientSessionChannel metaHandshake = bayeuxClient.getChannel(Channel.META_HANDSHAKE);
        metaHandshake.addListener((MessageListener)(channel, handshakeReply) -> {
            if (handshakeReply.isSuccessful()) {
                // Here the handshake with the server is successful.
            }
        });
        bayeuxClient.handshake();
        // end::listenerHandshake[]
    }

    public void blockingHandshake() throws Exception {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        ClientTransport transport = new JettyHttpClientTransport(null, httpClient);
        // tag::blockingHandshake[]
        BayeuxClient client = new BayeuxClient("http://localhost:8080/cometd", transport);
        client.handshake();
        boolean handshaked = client.waitFor(1000, BayeuxClient.State.CONNECTED);
        if (handshaked) {
            // Here the handshake with the server is successful.
        }
        // end::blockingHandshake[]
    }
}
