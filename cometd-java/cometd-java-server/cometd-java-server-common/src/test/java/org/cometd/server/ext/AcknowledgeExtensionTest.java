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
package org.cometd.server.ext;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.junit.Assert;
import org.junit.Test;

public class AcknowledgeExtensionTest extends AbstractBayeuxClientServerTest {
    public AcknowledgeExtensionTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testMetaConnectResendReturnsUnacknowledgedMessages() throws Exception {
        timeout = 5000;
        startServer(null);
        bayeux.addExtension(new AcknowledgedMessagesExtension());

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]," +
                "\"ext\": { \"ack\": true }" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"," +
                "\"ext\": { \"ack\": -1 }" +
                "}]");
        response = connect.send();
        Assert.assertEquals(200, response.getStatus());

        String channel = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = subscribe.send();
        Assert.assertEquals(200, response.getStatus());

        connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"," +
                "\"ext\": { \"ack\": 0 }" +
                "}]");
        connect.send(null);
        // Wait for the long poll.
        Thread.sleep(1000);

        // Stop the connector so a server-side publish will get lost.
        int port = connector.getLocalPort();
        connector.stop();
        // Wait to process the close.
        Thread.sleep(1000);

        ServerSessionImpl session = (ServerSessionImpl)bayeux.getSession(clientId);

        // Publish the message; it will get lost but the
        // ack extension will track it and resend it later.
        String data = "data";
        bayeux.getChannel(channel).publish(null, data, Promise.noop());
        // Wait for the message to be lost.
        Thread.sleep(1000);
        Assert.assertEquals(0, session.getQueue().size());

        connector.setPort(port);
        connector.start();

        // Be sure there is one message in the unacknowledged queue.
        AcknowledgedMessagesSessionExtension extension = (AcknowledgedMessagesSessionExtension)session.getExtensions().get(0);
        BatchArrayQueue<ServerMessage> ackQueue = extension.getBatchArrayQueue();
        Assert.assertEquals(1, ackQueue.size());

        // Send the same /meta/connect *without* advice: { timeout: 0 }.
        connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"," +
                "\"ext\": { \"ack\": 0 }" +
                "}]");
        FutureResponseListener listener = new FutureResponseListener(connect);
        connect.send(listener);

        // It must return immediately because there is a message in the unacknowledged queue.
        response = listener.get(1, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        JSONContext.Client parser = new JettyJSONContextClient();
        Message.Mutable[] messages = parser.parse(response.getContentAsString());
        Assert.assertEquals(2, messages.length);
        Message.Mutable m1 = messages[0];
        Message.Mutable m2 = messages[1];
        if (channel.equals(m1.getChannel())) {
            Assert.assertEquals(Channel.META_CONNECT, m2.getChannel());
            Assert.assertEquals(data, m1.getData());
        } else {
            Assert.assertEquals(Channel.META_CONNECT, m1.getChannel());
            Assert.assertEquals(channel, m2.getChannel());
            Assert.assertEquals(data, m2.getData());
        }

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testMetaConnectResendHoldsUnacknowledgedLazyMessages() throws Exception {
        timeout = 5000;
        startServer(null);
        bayeux.addExtension(new AcknowledgedMessagesExtension());

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]," +
                "\"ext\": { \"ack\": true }" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"," +
                "\"ext\": { \"ack\": -1 }" +
                "}]");
        response = connect.send();
        Assert.assertEquals(200, response.getStatus());

        String channel = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = subscribe.send();
        Assert.assertEquals(200, response.getStatus());

        connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"," +
                "\"ext\": { \"ack\": 0 }" +
                "}]");
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
        Assert.assertEquals(1, session.getQueue().size());

        connector.setPort(port);
        connector.start();

        // Be sure there is one message in the unacknowledged queue.
        AcknowledgedMessagesSessionExtension extension = (AcknowledgedMessagesSessionExtension)session.getExtensions().get(0);
        BatchArrayQueue<ServerMessage> ackQueue = extension.getBatchArrayQueue();
        Assert.assertEquals(1, ackQueue.size());

        // Send the same /meta/connect *without* advice: { timeout: 0 }.
        connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"," +
                "\"ext\": { \"ack\": 0 }" +
                "}]");
        FutureResponseListener listener = new FutureResponseListener(connect);
        connect.send(listener);

        // It must be held because there are only lazy messages.
        try {
            listener.get(1, TimeUnit.SECONDS);
            Assert.fail();
        } catch (TimeoutException x) {
            // Expected.
        }

        response = listener.get(2 * timeout, TimeUnit.MILLISECONDS);
        Assert.assertEquals(200, response.getStatus());

        JSONContext.Client parser = new JettyJSONContextClient();
        Message.Mutable[] messages = parser.parse(response.getContentAsString());
        Assert.assertEquals(2, messages.length);
        Message.Mutable m1 = messages[0];
        Message.Mutable m2 = messages[1];
        if (channel.equals(m1.getChannel())) {
            Assert.assertEquals(Channel.META_CONNECT, m2.getChannel());
            Assert.assertEquals(data, m1.getData());
        } else {
            Assert.assertEquals(Channel.META_CONNECT, m1.getChannel());
            Assert.assertEquals(channel, m2.getChannel());
            Assert.assertEquals(data, m2.getData());
        }

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assert.assertEquals(200, response.getStatus());
    }
}
