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
package org.cometd.javascript;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.Assert;
import org.junit.Test;

public class CometDWebSocketUnsubscribeFailureTest extends AbstractCometDWebSocketTest {
    @Test
    public void testUnsubscribeFailure() throws Exception {
        bayeuxServer.addExtension(new DeleteMetaUnsubscribeExtension());

        defineClass(Latch.class);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', readyLatch, 'countDown');");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'})");
        Assert.assertTrue(readyLatch.await(5000));

        // Wait for the long poll to establish
        Thread.sleep(1000);

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = get("subscribeLatch");
        evaluateScript("cometd.addListener('/meta/subscribe', subscribeLatch, subscribeLatch.countDown);");
        evaluateScript("var subscription = cometd.subscribe('/echo', subscribeLatch, subscribeLatch.countDown);");
        Assert.assertTrue(subscribeLatch.await(5000));

        evaluateScript("var unsubscribeLatch = new Latch(1);");
        Latch unsubscribeLatch = get("unsubscribeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("cometd.addListener('/meta/unsubscribe', unsubscribeLatch, unsubscribeLatch.countDown);");
        evaluateScript("cometd.addListener('/meta/unsuccessful', failureLatch, failureLatch.countDown);");
        evaluateScript("cometd.unsubscribe(subscription);");
        Assert.assertTrue(unsubscribeLatch.await(5000));
        Assert.assertTrue(failureLatch.await(5000));

        evaluateScript("cometd.disconnect(true);");
    }

    public static class DeleteMetaUnsubscribeExtension extends BayeuxServer.Extension.Adapter {
        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            return !Channel.META_UNSUBSCRIBE.equals(message.getChannel());
        }
    }
}
