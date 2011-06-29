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

import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.junit.Assert;
import org.junit.Test;

public class CometDDeliverTest extends AbstractCometDTest
{
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        new DeliverService(bayeux);
    }

    @Test
    public void testDeliver() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("var pushLatch = new Latch(1);");
        Latch pushLatch = get("pushLatch");
        evaluateScript("var _data;");
        evaluateScript("cometd.addListener('/service/deliver', function(message) { _data = message.data; pushLatch.countDown(); });");

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(1000));

        evaluateScript("var latch = new Latch(1);");
        Latch latch = get("latch");
        evaluateScript("var listener = cometd.addListener('/meta/publish', function(message) { latch.countDown(); });");
        evaluateScript("cometd.publish('/service/deliver', { deliver: false });");
        Assert.assertTrue(latch.await(1000));
        Assert.assertFalse(pushLatch.await(1000));
        evaluateScript("cometd.removeListener(listener);");
        evaluateScript("window.assert(_data === undefined);");

        latch.reset(1);
        evaluateScript("listener = cometd.addListener('/meta/publish', function(message) { latch.countDown(); });");
        evaluateScript("cometd.publish('/service/deliver', { deliver: true });");
        Assert.assertTrue(latch.await(1000));
        evaluateScript("cometd.removeListener(listener);");
        // Wait for the listener to be notified from the server
        Assert.assertTrue(pushLatch.await(1000));
        evaluateScript("window.assert(_data !== undefined);");

        evaluateScript("cometd.disconnect(true);");
    }

    public static class DeliverService extends AbstractService
    {
        public DeliverService(BayeuxServerImpl bayeux)
        {
            super(bayeux, "deliver");
            addService("/service/deliver", "deliver");
        }

        public void deliver(ServerSession remote, String channel, Object messageData, String messageId)
        {
            Map<String, Object> data = (Map<String, Object>)messageData;
            Boolean deliver = (Boolean) data.get("deliver");
            if (deliver)
            {
                remote.deliver(getServerSession(), channel, data, messageId);
            }
        }
    }
}
