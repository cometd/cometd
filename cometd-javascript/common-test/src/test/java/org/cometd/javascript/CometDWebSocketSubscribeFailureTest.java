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

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.Assert;
import org.junit.Test;

public class CometDWebSocketSubscribeFailureTest extends AbstractCometDWebSocketTest
{
    @Test
    public void testSubscribeFailure() throws Exception
    {
        bayeuxServer.addExtension(new SubscribeThrowingExtension());

        defineClass(Latch.class);
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'})");
        Assert.assertTrue(readyLatch.await(5000));

        // Wait a while for the connect to establish
        Thread.sleep(1000);

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = get("subscribeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("var connectFailureLatch = new Latch(1);");
        Latch connectFailureLatch = get("connectFailureLatch");
        evaluateScript("var connectRestoredLatch = new Latch(1);");
        Latch connectRestoredLatch = get("connectRestoredLatch");
        evaluateScript("cometd.addListener('/meta/subscribe', subscribeLatch, subscribeLatch.countDown);");
        evaluateScript("cometd.addListener('/meta/unsuccessful', failureLatch, failureLatch.countDown);");
        evaluateScript("cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "    if (message.successful === true)" +
                "        connectRestoredLatch.countDown();" +
                "    else if (message.successful === false)" +
                "        connectFailureLatch.countDown();" +
                "});");
        evaluateScript("cometd.subscribe('/echo', subscribeLatch, subscribeLatch.countDown);");
        Assert.assertTrue(subscribeLatch.await(5000));
        Assert.assertTrue(failureLatch.await(5000));
        // WebSocket uses only one connection, therefore also the connect fails
        Assert.assertTrue(connectFailureLatch.await(5000));
        // Be sure there is a new connect issued
        Assert.assertTrue(connectRestoredLatch.await(5000));

        // Be sure the backoff has been reset
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        int backoff = ((Number)get("backoff")).intValue();
        Assert.assertEquals(0, backoff);

        evaluateScript("cometd.disconnect(true);");
    }

    public static class SubscribeThrowingExtension extends BayeuxServer.Extension.Adapter
    {
        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message)
        {
            if (Channel.META_SUBSCRIBE.equals(message.getChannel()))
                throw new Error("explicitly_thrown_by_test");
            return true;
        }
    }
}
