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

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.eclipse.jetty.client.HttpClient;

@SuppressWarnings("unused")
public class ClientSubscribeDocs {
    private final HttpClient httpClient = new HttpClient();
    private final ClientTransport transport = new JettyHttpClientTransport(null, httpClient);

    public void simpleSubscribe() {
        // tag::simpleSubscribe[]
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        bayeuxClient.handshake(handshakeReply -> {
            // You can only subscribe after a successful handshake.
            if (handshakeReply.isSuccessful()) {
                // The channel you want to subscribe to.
                ClientSessionChannel channel = bayeuxClient.getChannel("/stocks");

                // The message listener invoked every time a message is received from the server.
                ClientSessionChannel.MessageListener messageListener = (c, message) -> {
                    // Here you received a message on the channel.
                };

                // Send the subscription to the server.
                channel.subscribe(messageListener);
            }
        });
        // end::simpleSubscribe[]
    }

    public void simpleUnsubscribe() {
        // tag::simpleUnsubscribe[]
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        bayeuxClient.handshake(handshakeReply -> {
            // You can only subscribe after a successful handshake.
            if (handshakeReply.isSuccessful()) {
                // The channel you want to subscribe to.
                ClientSessionChannel channel = bayeuxClient.getChannel("/stocks");

                // The message listener invoked every time a message is received from the server.
                ClientSessionChannel.MessageListener messageListener = (c, message) -> {
                    // Here you received a message on the channel.
                };

                // Send the subscription to the server.
                channel.subscribe(messageListener);

                // In some other part of the code:
                // Send the unsubscription to the server.
                channel.unsubscribe(messageListener);
            }
        });
        // end::simpleUnsubscribe[]
    }

    public void callbackSubscribe() {
        // tag::callbackSubscribe[]
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);
        bayeuxClient.handshake(handshakeReply -> {
            // You can only subscribe after a successful handshake.
            if (handshakeReply.isSuccessful()) {
                // The message listener invoked every time a message is received from the server.
                ClientSessionChannel.MessageListener messageListener = (c, message) -> {
                    // Here you received a message on the channel.
                };

                bayeuxClient.getChannel("/stocks").subscribe(messageListener, subscribeReply -> {
                    if (subscribeReply.isSuccessful()) {
                        // The subscription successful on the server.
                    }
                });
            }
        });
        // end::callbackSubscribe[]
    }

    public void metaListener() {
        // tag::metaListener[]
        BayeuxClient bayeuxClient = new BayeuxClient("http://localhost:8080/cometd", transport);

        // A listener for the /meta/connect channel.
        // Listeners are persistent across re-handshakes and
        // can be configured before handshaking with the server.
        bayeuxClient.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                // Connected to the server.
            } else {
                // Disconnected from the server.
            }
        });

        // Handshake with the server.
        bayeuxClient.handshake();
        // end::metaListener[]
    }
}
