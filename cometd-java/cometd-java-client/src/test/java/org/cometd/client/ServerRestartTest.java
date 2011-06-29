/*
 * Copyright (c) 2010 the original author or authors.
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

import java.net.ConnectException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.transport.LongPollingTransport;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ServerRestartTest extends ClientServerTest
{
    @Before
    public void init() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testServerRestart() throws Exception
    {
        final AtomicReference<CountDownLatch> sendLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(3));
        BayeuxClient client = new BayeuxClient(cometdURL, LongPollingTransport.create(null, httpClient))
        {
            @Override
            public void onSending(Message[] messages)
            {
                super.onSending(messages);
                sendLatch.get().countDown();
            }

            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Suppress expected exception logging
                if (!(x instanceof ConnectException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());
        long backoffIncrement = 500;
        client.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, backoffIncrement);
        client.handshake();

        // Be sure the second connect has been sent to the server
        assertTrue(sendLatch.get().await(1, TimeUnit.SECONDS));

        // Wait a little more
        Thread.sleep(1000);

        int port = connector.getLocalPort();
        server.stop();
        server.join();

        // Wait a few retries
        Thread.sleep(backoffIncrement + 2 * backoffIncrement + 3 * backoffIncrement);

        // Add listeners to check the behavior of the client
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                handshakeLatch.countDown();
            }
        });
        final CountDownLatch connectLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                connectLatch.countDown();
            }
        });
        // Expect handshake and 2 connects messages to be sent
        sendLatch.set(new CountDownLatch(3));

        connector.setPort(port);
        server.start();

        assertTrue(handshakeLatch.await(5 * backoffIncrement, TimeUnit.MILLISECONDS));
        assertTrue(connectLatch.await(1, TimeUnit.SECONDS));
        assertTrue(sendLatch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
