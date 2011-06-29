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

package org.cometd.javascript;

import java.util.Map;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that handshake properties are passed correctly during handshake
 */
public class CometDHandshakePropsTest extends AbstractCometDTest
{
    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        bayeux.setSecurityPolicy(new TokenSecurityPolicy());
    }

    @Test
    public void testHandshakeProps() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("cometd.addListener('/meta/handshake', handshakeLatch, handshakeLatch.countDown);");
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");

        // Start without the token; this makes the handshake fail
        evaluateScript("cometd.handshake({})");
        Assert.assertTrue(handshakeLatch.await(1000));
        // A failed handshake arrives with an advice to not reconnect
        Assert.assertEquals("disconnected", evaluateScript("cometd.getStatus()"));

        // We are already initialized, handshake again with a token
        handshakeLatch.reset(1);
        evaluateScript("cometd.handshake({ext: {token: 'test'}})");
        Assert.assertTrue(handshakeLatch.await(1000));

        // Wait for the long poll to happen, so that we're sure
        // the disconnect is sent after the long poll
        Thread.sleep(1000);

        Assert.assertEquals("connected", evaluateScript("cometd.getStatus();"));

        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(1000));

        Assert.assertEquals("disconnected", evaluateScript("cometd.getStatus();"));
    }

    private class TokenSecurityPolicy implements SecurityPolicy
    {
        public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
        {
            Map<String, Object> ext = message.getExt();
            return ext != null && ext.containsKey("token");
        }

        public boolean canCreate(BayeuxServer server, ServerSession session, String channelId, ServerMessage message)
        {
            return true;
        }

        public boolean canSubscribe(BayeuxServer server, ServerSession client, ServerChannel channel, ServerMessage messsage)
        {
            return true;
        }

        public boolean canPublish(BayeuxServer server, ServerSession client, ServerChannel channel, ServerMessage messsage)
        {
            return true;
        }
    }
}
