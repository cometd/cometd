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

import java.net.ConnectException;
import java.net.ProtocolException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.websocket.ClientServerWebSocketTest;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BayeuxClientWebSocketTest extends ClientServerWebSocketTest
{
    @Before
    public void init() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testClientCanNegotiateTransportWithServerNotSupportingWebSocket() throws Exception
    {
        bayeux.setAllowedTransports("long-polling");

        WebSocketTransport webSocketTransport = WebSocketTransport.create(null);
        webSocketTransport.setDebugEnabled(debugTests());
        LongPollingTransport longPollingTransport = LongPollingTransport.create(null);
        longPollingTransport.setDebugEnabled(debugTests());
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Expect exception and suppress stack trace logging
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

        disconnectBayeuxClient(client);
    }

    @Test
    public void testClientRetriesWebSocketTransportIfCannotConnect() throws Exception
    {
        int port = connector.getLocalPort();
        stopServer();

        final CountDownLatch connectLatch = new CountDownLatch(2);
        WebSocketTransport webSocketTransport = WebSocketTransport.create(null);
        webSocketTransport.setDebugEnabled(debugTests());
        LongPollingTransport longPollingTransport = LongPollingTransport.create(null);
        longPollingTransport.setDebugEnabled(debugTests());
        final BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport, longPollingTransport)
        {
            @Override
            protected boolean sendConnect()
            {
                if ("websocket".equals(getTransport().getName()))
                    connectLatch.countDown();
                return super.sendConnect();
            }

            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Expect exception and suppress stack trace logging
                if (!(x instanceof ConnectException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());

        final CountDownLatch failedLatch = new CountDownLatch(1);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (!message.isSuccessful())
                    failedLatch.countDown();
            }
        });

        client.handshake();

        Assert.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));

        connector.setPort(port);
        server.start();

        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testAbortThenRestart() throws Exception
    {
        final AtomicReference<CountDownLatch> connectLatch = new AtomicReference<CountDownLatch>(new CountDownLatch(2));
        WebSocketTransport webSocketTransport = WebSocketTransport.create(null);
        webSocketTransport.setDebugEnabled(debugTests());
        BayeuxClient client = new BayeuxClient(cometdURL, webSocketTransport)
        {
            @Override
            public void onSending(Message[] messages)
            {
                // Need to be sure that the second connect is sent otherwise
                // the abort and rehandshake may happen before the second
                // connect and the test will fail.
                super.onSending(messages);
                if (messages.length == 1 && Channel.META_CONNECT.equals(messages[0].getChannel()))
                    connectLatch.get().countDown();
            }
        };
        client.setDebugEnabled(debugTests());
        client.handshake();

        // Wait for connect
        Assert.assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));

        client.abort();
        Assert.assertFalse(client.isConnected());

        // Restart
        connectLatch.set(new CountDownLatch(2));
        client.handshake();
        Assert.assertTrue(connectLatch.get().await(1000, TimeUnit.MILLISECONDS));
        Assert.assertTrue(client.isConnected());

        disconnectBayeuxClient(client);
    }

    @Test
    public void testHandshakeExpiration() throws Exception
    {
        final long maxNetworkDelay = 2000;

        bayeux.getChannel(Channel.META_HANDSHAKE).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                try
                {
                    Thread.sleep(maxNetworkDelay + maxNetworkDelay / 2);
                    return true;
                }
                catch (InterruptedException x)
                {
                    return false;
                }
            }
        });

        Map<String,Object> options = new HashMap<String, Object>();
        options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        WebSocketTransport transport = WebSocketTransport.create(options);
        transport.setDebugEnabled(debugTests());
        final BayeuxClient client = new BayeuxClient(cometdURL, transport)
        {
            @Override
            public void onFailure(Throwable x, Message[] messages)
            {
                // Expect exception and suppress stack trace logging
                if (!(x instanceof TimeoutException))
                    super.onFailure(x, messages);
            }
        };
        client.setDebugEnabled(debugTests());

        // Expect 2 failed messages because the client backoffs and retries
        // This way we are sure that the late response from the first
        // expired handshake is not delivered to listeners
        final CountDownLatch latch = new CountDownLatch(2);
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                Assert.assertFalse(message.isSuccessful());
                if (!message.isSuccessful())
                    latch.countDown();
            }
        });

        client.handshake();

        Assert.assertTrue(latch.await(maxNetworkDelay * 2 + client.getBackoffIncrement() * 2, TimeUnit.MILLISECONDS));

        disconnectBayeuxClient(client);
    }
}
