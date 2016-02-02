/*
 * Copyright (c) 2008-2016 the original author or authors.
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SimulatedNetworkFailureTest extends AbstractClientServerTest
{
    private long timeout = 10000;
    private long maxInterval = 8000;
    private long sweepInterval = 1000;

    public SimulatedNetworkFailureTest(Transport transport)
    {
        super(transport);
    }

    @Before
    public void setUp() throws Exception
    {
        Map<String, String> options = serverOptions();
        options.put("timeout", String.valueOf(timeout));
        options.put("maxInterval", String.valueOf(maxInterval));
        options.put("sweepIntervalMs", String.valueOf(sweepInterval));
        startServer(options);
    }

    @Test
    public void testClientShortNetworkFailure() throws Exception
    {
        final CountDownLatch connectLatch = new CountDownLatch(2);
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);

        TestBayeuxClient client = new TestBayeuxClient()
        {
            @Override
            protected boolean sendConnect()
            {
                boolean result = super.sendConnect();
                connectLatch.countDown();
                return result;
            }
        };
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                boolean wasConnected = connected.get();
                connected.set(message.isSuccessful());

                if (!wasConnected && connected.get())
                    System.err.printf("BayeuxClient connected %s%n", message);
                else if (wasConnected && !connected.get())
                    System.err.printf("BayeuxClient unconnected %s%n", message);
            }
        });
        String channelName = "/test";
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    publishLatch.get().countDown();
            }
        });

        client.handshake();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        // Wait for a second connect to be issued
        Thread.sleep(1000);

        long networkDown = maxInterval / 2;
        client.setNetworkDown(networkDown);

        // Publish, it must succeed
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap<>());
        assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));

        // Wait for the connect to return
        // We already slept a bit before, so we are sure that the connect returned
        Thread.sleep(timeout);

        // Now we are simulating the network is down
        // Be sure we are disconnected
        assertFalse(connected.get());

        // Another publish, it must fail
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap<>());
        assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));

        // Sleep to allow the next connect to be issued
        Thread.sleep(networkDown);

        // Now another connect has been sent (delayed by 'networkDown' ms)
        Thread.sleep(timeout);
        assertTrue(connected.get());

        // We should be able to publish now
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap<>());
        assertFalse(publishLatch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testClientLongNetworkFailure() throws Exception
    {
        final CountDownLatch connectLatch = new CountDownLatch(2);
        final CountDownLatch handshakeLatch = new CountDownLatch(2);
        final AtomicReference<CountDownLatch> publishLatch = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);

        TestBayeuxClient client = new TestBayeuxClient()
        {
            @Override
            protected boolean sendConnect()
            {
                boolean result = super.sendConnect();
                connectLatch.countDown();
                return result;
            }
        };
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                    handshakeLatch.countDown();
            }
        });
        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                boolean wasConnected = connected.get();
                connected.set(message.isSuccessful());

                if (!wasConnected && connected.get())
                    System.err.println("BayeuxClient connected");
                else if (wasConnected && !connected.get())
                    System.err.println("BayeuxClient unconnected");
            }
        });
        String channelName = "/test";
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    publishLatch.get().countDown();
            }
        });
        client.handshake();
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        // Wait for a second connect to be issued
        Thread.sleep(1000);

        // Add some margin since the session is swept every 'sweepIntervalMs'
        long networkDown = maxInterval + 3 * sweepInterval;
        client.setNetworkDown(networkDown);

        // Publish, it must succeed
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap<>());
        assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));

        // Wait for the connect to return
        // We already slept a bit before, so we are sure that the connect returned
        Thread.sleep(timeout);

        // Now we are simulating the network is down
        // Be sure we are disconnected
        assertFalse(connected.get());

        // Another publish, it must fail
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap<>());
        assertTrue(publishLatch.get().await(5, TimeUnit.SECONDS));

        // Sleep to allow the next connect to be issued
        Thread.sleep(networkDown);

        // Now another connect has been sent (delayed by 'networkDown' ms)
        // but the server expired the client, so we handshake again
        assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));

        // We should be able to publish now
        publishLatch.set(new CountDownLatch(1));
        channel.publish(new HashMap<>());
        assertFalse(publishLatch.get().await(1, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    private class TestBayeuxClient extends BayeuxClient
    {
        private long networkDown;

        private TestBayeuxClient()
        {
            super(cometdURL, newClientTransport(null));
        }

        public void setNetworkDown(long time)
        {
            this.networkDown = time;
            System.err.println("Set network down");
        }

        @Override
        protected void processConnect(Message.Mutable connect)
        {
            if (networkDown > 0)
                connect.setSuccessful(false);
            super.processConnect(connect);
        }

        @Override
        protected boolean scheduleConnect(long interval, long backOff)
        {
            if (networkDown > 0)
                backOff = networkDown;
            return super.scheduleConnect(interval, backOff);
        }

        @Override
        protected boolean sendConnect()
        {
            if (networkDown > 0)
            {
                networkDown = 0;
                System.err.println("Reset network down");
            }
            return super.sendConnect();
        }

        @Override
        protected void enqueueSend(Message.Mutable message)
        {
            if (networkDown > 0)
            {
                List<Message.Mutable> messages = new ArrayList<>(1);
                messages.add(message);
                failMessages(null, messages);
            }
            else
            {
                super.enqueueSend(message);
            }
        }
    }
}
