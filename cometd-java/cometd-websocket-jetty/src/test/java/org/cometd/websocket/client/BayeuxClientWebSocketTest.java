/*
 * Copyright (c) 2011 the original author or authors.
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

package org.cometd.websocket.client;

import java.net.ProtocolException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.websocket.ClientServerWebSocketTest;
import org.junit.Assert;
import org.junit.Test;

public class BayeuxClientWebSocketTest extends ClientServerWebSocketTest
{
    @Test
    public void testClientCanNegotiateTransportWithServerWithoutWebSocket() throws Exception
    {
        startServer(null);

        bayeux.setAllowedTransports("long-polling");

        final BayeuxClient client = new BayeuxClient(cometdURL, WebSocketTransport.create(null), LongPollingTransport.create(null))
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Expect a ProtocolException and suppress stack trace
                if (!(x instanceof ProtocolException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());

        final CountDownLatch successLatch = new CountDownLatch(1);
        final CountDownLatch failedLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    successLatch.countDown();
                else
                    failedLatch.countDown();
            }
        });

        client.handshake();

        Assert.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(successLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(client.waitFor(1000, BayeuxClient.State.CONNECTED));

        disconnectBayeuxClient(client);
    }
}
