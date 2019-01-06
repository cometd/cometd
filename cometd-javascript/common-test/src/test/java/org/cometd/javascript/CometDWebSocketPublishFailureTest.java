/*
 * Copyright (c) 2008-2019 the original author or authors.
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

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.Assert;
import org.junit.Test;

public class CometDWebSocketPublishFailureTest extends AbstractCometDWebSocketTest {
    @Test
    public void testPublishFailure() throws Exception {
        bayeuxServer.addExtension(new DeletePublishExtension());

        defineClass(Latch.class);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', readyLatch, 'countDown');");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'})");
        Assert.assertTrue(readyLatch.await(5000));

        // Wait a while for the connect to establish
        Thread.sleep(1000);

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = get("subscribeLatch");
        evaluateScript("cometd.addListener('/meta/subscribe', subscribeLatch, subscribeLatch.countDown);");
        evaluateScript("var subscription = cometd.subscribe('/echo', subscribeLatch, subscribeLatch.countDown);");
        Assert.assertTrue(subscribeLatch.await(5000));

        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = get("publishLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("cometd.addListener('/meta/publish', publishLatch, publishLatch.countDown);");
        evaluateScript("cometd.addListener('/meta/unsuccessful', failureLatch, failureLatch.countDown);");
        evaluateScript("cometd.publish('/echo', 'test');");
        Assert.assertTrue(publishLatch.await(5000));
        Assert.assertTrue(failureLatch.await(5000));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(5000));
        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);
    }

    private static class DeletePublishExtension extends BayeuxServer.Extension.Adapter {
        @Override
        public boolean rcv(ServerSession from, ServerMessage.Mutable message) {
            // The publish will arrive here, just delete it
            return false;
        }
    }
}
