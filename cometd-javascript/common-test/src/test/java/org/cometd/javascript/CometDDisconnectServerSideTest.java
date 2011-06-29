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

package org.cometd.javascript;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.junit.Assert;
import org.junit.Test;

public class CometDDisconnectServerSideTest extends AbstractCometDTest
{
    private BayeuxServer bayeux;

    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        this.bayeux = bayeux;
    }

    @Test
    public void testServerSideDisconnect() throws Exception
    {
        final CountDownLatch connectRequestLatch = new CountDownLatch(1);
        class S extends AbstractService
        {
            private final AtomicInteger connects = new AtomicInteger();

            S()
            {
                super(bayeux, "test_server_side_disconnect");
                addService(Channel.META_CONNECT, "processMetaConnect");
            }

            public void processMetaConnect(ServerSession session, ServerMessage message)
            {
                if (connects.incrementAndGet() == 2)
                    connectRequestLatch.countDown();
            }

            public void disconnect(String sessionId)
            {
                ServerSession session = getBayeux().getSession(sessionId);
                session.disconnect();
            }
        }

        S service = new S();

        defineClass(Latch.class);
        evaluateScript("var connectLatch = new Latch(2);");
        Latch connectResponseLatch = get("connectLatch");
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/connect', connectLatch, 'countDown');" +
                "cometd.addListener('/meta/disconnect', disconnectLatch, 'countDown');" +
                "cometd.handshake();" +
                "");
        Assert.assertTrue(connectRequestLatch.await(5, TimeUnit.SECONDS));

        service.disconnect((String)evaluateScript("cometd.getClientId()"));

        Assert.assertTrue(disconnectLatch.await(5000));
        Assert.assertTrue(connectResponseLatch.await(5000));
    }

    @Test
    public void testDeliverAndServerSideDisconnect() throws Exception
    {
        final String channelName = "/service/kick";

        final CountDownLatch connectLatch = new CountDownLatch(1);
        class S extends AbstractService
        {
            private final AtomicInteger connects = new AtomicInteger();

            S()
            {
                super(bayeux, "test_disconnect_with_messages");
                addService(Channel.META_CONNECT, "processMetaConnect");
            }

            public void processMetaConnect(ServerSession session, ServerMessage message)
            {
                if (connects.incrementAndGet() == 2)
                    connectLatch.countDown();
            }

            public void kick(String sessionId)
            {
                final ServerMessage.Mutable kickMessage = bayeux.newMessage();
                kickMessage.setChannel(channelName);
                kickMessage.setData(new HashMap());

                final ServerSession session = getBayeux().getSession(sessionId);

                // We need to batch otherwise the deliver() will wake up the long poll
                // and the disconnect may not be delivered, since the client won't issue
                // a new long poll, and the disconnect will remain in the queue
                session.batch(new Runnable()
                {
                    public void run()
                    {
                        session.deliver(getServerSession(), kickMessage);
                        session.disconnect();
                    }
                });
            }
        }
        S service = new S();

        defineClass(Latch.class);
        evaluateScript("var deliverLatch = new Latch(1);");
        Latch deliverLatch = get("deliverLatch");
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/disconnect', disconnectLatch, 'countDown');" +
                "cometd.addListener('/meta/handshake', function(message)" +
                "{" +
                "    if (message.successful)" +
                "        cometd.addListener('" + channelName + "', deliverLatch, 'countDown');" +
                "});" +
                "cometd.handshake();" +
                "");
        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        service.kick((String)evaluateScript("cometd.getClientId()"));

        Assert.assertTrue(deliverLatch.await(5000));
        Assert.assertTrue(disconnectLatch.await(5000));
    }
}
