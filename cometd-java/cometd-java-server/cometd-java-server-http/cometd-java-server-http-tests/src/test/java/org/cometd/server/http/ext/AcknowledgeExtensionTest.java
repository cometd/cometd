/*
 * Copyright (c) 2008 the original author or authors.
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

package org.cometd.server.http.ext;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.server.ext.AcknowledgedMessagesSessionExtension;
import org.cometd.server.ext.BatchArrayQueue;
import org.cometd.server.http.AbstractBayeuxClientServerTest;
import org.cometd.server.http.Transport;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class AcknowledgeExtensionTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testMetaConnectResendReturnsUnacknowledgedMessages(Transport transport) throws Exception {
        timeout = 5000;
        startServer(transport, null);
        bayeux.addExtension(new AcknowledgedMessagesExtension());

        Request handshake = newBayeuxRequest("""
                [{
                "id": "0",
                "channel": "/meta/handshake",
                "version": "1.0",
                "minimumVersion": "1.0",
                "supportedConnectionTypes": ["long-polling"],
                "ext": { "ack": true }
                }]
                """);
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("""
                [{
                "id": "1",
                "channel": "/meta/connect",
                "clientId": "%s",
                "connectionType": "long-polling",
                "ext": { "ack": -1 }
                }]
                """.formatted(clientId));
        response = connect.send();
        Assertions.assertEquals(200, response.getStatus());

        String channel = "/foo";
        Request subscribe = newBayeuxRequest("""
                [{
                "id": "2",
                "channel": "/meta/subscribe",
                "clientId": "%s",
                "subscription": "%s"
                }]
                """.formatted(clientId, channel));
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());

        connect = newBayeuxRequest("""
                [{
                "id": "3",
                "channel": "/meta/connect",
                "clientId": "%s",
                "connectionType": "long-polling",
                "ext": { "ack": 0 }
                }]""".formatted(clientId));
        connect.send(null);
        // Wait for the long poll.
        Thread.sleep(1000);

        // Stop the connector so a server-side publish will not be delivered.
        int port = connector.getLocalPort();
        connector.stop();
        // Wait to process the close.
        Thread.sleep(1000);

        ServerSessionImpl session = (ServerSessionImpl)bayeux.getSession(clientId);

        // Publish the message; the ack extension will track it and resend it later.
        String data = "data";
        bayeux.getChannel(channel).publish(null, data, Promise.noop());
        // Wait for the message to be lost.
        Thread.sleep(1000);

        Assertions.assertEquals(1, session.getQueue().size());

        connector.setPort(port);
        connector.start();

        // Be sure there is one message in the unacknowledged queue.
        AcknowledgedMessagesSessionExtension extension = (AcknowledgedMessagesSessionExtension)session.getExtensions().get(0);
        BatchArrayQueue<ServerMessage> ackQueue = extension.getBatchArrayQueue();
        Assertions.assertEquals(1, ackQueue.size());

        // Send the same /meta/connect *without* advice: { timeout: 0 }.
        connect = newBayeuxRequest("""
                [{
                "id": "4",
                "channel": "/meta/connect",
                "clientId": "%s",
                "connectionType": "long-polling",
                "ext": { "ack": 0 }
                }]
                """.formatted(clientId));
        CompletableFuture<ContentResponse> listener = new CompletableResponseListener(connect).send();

        // It must return immediately because there is a message in the unacknowledged queue.
        response = listener.get(1, TimeUnit.SECONDS);
        Assertions.assertEquals(200, response.getStatus());

        JSONContext.Client parser = new JettyJSONContextClient();
        List<Message.Mutable> messages = parser.parse(response.getContentAsString());
        Assertions.assertEquals(2, messages.size());
        Message.Mutable m1 = messages.get(0);
        Message.Mutable m2 = messages.get(1);
        if (channel.equals(m1.getChannel())) {
            Assertions.assertEquals(Channel.META_CONNECT, m2.getChannel());
            Assertions.assertEquals(data, m1.getData());
        } else {
            Assertions.assertEquals(Channel.META_CONNECT, m1.getChannel());
            Assertions.assertEquals(channel, m2.getChannel());
            Assertions.assertEquals(data, m2.getData());
        }

        Request disconnect = newBayeuxRequest("""
                [{
                "id": "5",
                "channel": "/meta/disconnect",
                "clientId": "%s"
                }]
                """.formatted(clientId));
        response = disconnect.send();
        Assertions.assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMetaConnectResendHoldsUnacknowledgedLazyMessages(Transport transport) throws Exception {
        timeout = 5000;
        startServer(transport, null);
        bayeux.addExtension(new AcknowledgedMessagesExtension());

        Request handshake = newBayeuxRequest("""
                [{
                "id": "0",
                "channel": "/meta/handshake",
                "version": "1.0",
                "minimumVersion": "1.0",
                "supportedConnectionTypes": ["long-polling"],
                "ext": { "ack": true }
                }]
                """);
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("""
                [{
                "id": "1",
                "channel": "/meta/connect",
                "clientId": "%s",
                "connectionType": "long-polling",
                "ext": { "ack": -1 }
                }]
                """.formatted(clientId));
        response = connect.send();
        Assertions.assertEquals(200, response.getStatus());

        String channel = "/foo";
        Request subscribe = newBayeuxRequest("""
                [{
                "id": "2",
                "channel": "/meta/subscribe",
                "clientId": "%s",
                "subscription": "%s"
                }]
                """.formatted(clientId, channel));
        response = subscribe.send();
        Assertions.assertEquals(200, response.getStatus());

        connect = newBayeuxRequest("""
                [{
                "id": "3",
                "channel": "/meta/connect",
                "clientId": "%s",
                "connectionType": "long-polling",
                "ext": { "ack": 0 }
                }]
                """.formatted(clientId));
        connect.send(null);
        // Wait for the long poll.
        Thread.sleep(1000);

        // Stop the connector so a server-side publish will get lost.
        int port = connector.getLocalPort();
        connector.stop();
        // Wait to process the close.
        Thread.sleep(1000);

        ServerSessionImpl session = (ServerSessionImpl)bayeux.getSession(clientId);

        // Publish the message; it won't be sent because it's lazy.
        String data = "data";
        ServerChannel serverChannel = bayeux.getChannel(channel);
        serverChannel.setLazy(true);
        serverChannel.publish(null, data, Promise.noop());
        Thread.sleep(1000);
        Assertions.assertEquals(1, session.getQueue().size());

        connector.setPort(port);
        connector.start();

        // Be sure there is one message in the unacknowledged queue.
        AcknowledgedMessagesSessionExtension extension = (AcknowledgedMessagesSessionExtension)session.getExtensions().get(0);
        BatchArrayQueue<ServerMessage> ackQueue = extension.getBatchArrayQueue();
        Assertions.assertEquals(1, ackQueue.size());

        // Send the same /meta/connect *without* advice: { timeout: 0 }.
        connect = newBayeuxRequest("""
                [{
                "id": "4",
                "channel": "/meta/connect",
                "clientId": "%s",
                "connectionType": "long-polling",
                "ext": { "ack": 0 }
                }]
                """.formatted(clientId));
        CompletableFuture<ContentResponse> listener = new CompletableResponseListener(connect).send();

        // It must be held because there are only lazy messages.
        try {
            response = listener.get(1, TimeUnit.SECONDS);
            Assertions.fail(response.toString());
        } catch (TimeoutException x) {
            // Expected.
        }

        response = listener.get(2 * timeout, TimeUnit.MILLISECONDS);
        Assertions.assertEquals(200, response.getStatus());

        JSONContext.Client parser = new JettyJSONContextClient();
        List<Message.Mutable> messages = parser.parse(response.getContentAsString());
        Assertions.assertEquals(2, messages.size());
        Message.Mutable m1 = messages.get(0);
        Message.Mutable m2 = messages.get(1);
        if (channel.equals(m1.getChannel())) {
            Assertions.assertEquals(Channel.META_CONNECT, m2.getChannel());
            Assertions.assertEquals(data, m1.getData());
        } else {
            Assertions.assertEquals(Channel.META_CONNECT, m1.getChannel());
            Assertions.assertEquals(channel, m2.getChannel());
            Assertions.assertEquals(data, m2.getData());
        }

        Request disconnect = newBayeuxRequest("""
                [{
                "id": "5",
                "channel": "/meta/disconnect",
                "clientId": "%s"
                }]
                """.formatted(clientId));
        response = disconnect.send();
        Assertions.assertEquals(200, response.getStatus());
    }
}
