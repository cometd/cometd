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
package org.cometd.client.http;

import java.net.HttpCookie;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.http.AbstractHttpTransport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HTTP2MultipleClientSessionsTest extends HTTP2ClientServerTest {
    @Test
    public void testMultipleClientSession_WithOneMaxSessionPerBrowser_WithMultiSessionInterval() throws Exception {
        long timeout = 7000;
        long multiSessionInterval = 1500;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        options.put(AbstractHttpTransport.MAX_SESSIONS_PER_BROWSER_OPTION, "1");
        options.put(AbstractHttpTransport.MULTI_SESSION_INTERVAL_OPTION, String.valueOf(multiSessionInterval));
        start(options);

        BayeuxClient client1 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects1 = new ConcurrentLinkedQueue<>();
        client1.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                connects1.offer(message);
            }
        });
        client1.handshake();
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        HttpCookie browserCookie = client1.getCookie("BAYEUX_BROWSER");
        Assertions.assertNotNull(browserCookie);

        // Give some time to the first client to establish the long poll before the second client
        Thread.sleep(1000);

        BayeuxClient client2 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects2 = new ConcurrentLinkedQueue<>();
        client2.putCookie(browserCookie);
        client2.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connects2.offer(message));
        client2.handshake();
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        Thread.sleep(1000);

        BayeuxClient client3 = newBayeuxClient();
        ConcurrentLinkedQueue<Message> connects3 = new ConcurrentLinkedQueue<>();
        client3.putCookie(browserCookie);
        client3.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> connects3.offer(message));
        client3.handshake();
        Assertions.assertTrue(client3.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Sleep for a while
        Thread.sleep(2 * multiSessionInterval);

        // All clients must remain in long poll mode.
        Assertions.assertEquals(1, connects1.size());
        Assertions.assertEquals(1, connects2.size());
        Assertions.assertEquals(1, connects3.size());

        // Wait for clients to re-issue a long poll.
        Thread.sleep(timeout);

        // All clients must still be in long poll mode
        Assertions.assertEquals(2, connects1.size());
        Assertions.assertEquals(2, connects2.size());
        Assertions.assertEquals(2, connects3.size());
        // None of the clients must have the multiple-clients advice.
        Message lastConnect = new LinkedList<>(connects1).getLast();
        Map<String, Object> advice = lastConnect.getAdvice();
        if (advice != null) {
            Assertions.assertFalse(advice.containsKey("multiple-clients"));
        }

        disconnectBayeuxClient(client1);
        disconnectBayeuxClient(client2);
        disconnectBayeuxClient(client3);
    }
}
