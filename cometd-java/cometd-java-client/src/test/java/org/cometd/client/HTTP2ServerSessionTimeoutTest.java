/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.junit.Assert;
import org.junit.Test;

public class HTTP2ServerSessionTimeoutTest extends HTTP2ClientServerTest {
    @Test
    public void testHTTP2ServerSessionTimeout() throws Exception {
        Map<String, String> options = new HashMap<>();
        long timeout = 2000;
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        long maxInterval = 3000;
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        start(options);

        CountDownLatch addLatch = new CountDownLatch(1);
        CountDownLatch removeLatch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession session, ServerMessage message) {
                addLatch.countDown();
            }

            @Override
            public void sessionRemoved(ServerSession session, boolean timedout) {
                removeLatch.countDown();
            }
        });

        BayeuxClient client = newBayeuxClient();
        AtomicInteger metaConnects = new AtomicInteger();
        client.addExtension(new ClientSession.Extension() {
            @Override
            public boolean sendMeta(ClientSession session, Message.Mutable message) {
                if (Channel.META_CONNECT.equals(message.getChannel())) {
                    return metaConnects.incrementAndGet() <= 2;
                }
                return true;
            }
        });

        client.handshake();

        Assert.assertTrue(addLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(removeLatch.await(timeout + 2 * maxInterval, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }
}
