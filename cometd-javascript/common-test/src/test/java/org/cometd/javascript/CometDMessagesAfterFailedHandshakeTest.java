/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import org.cometd.server.DefaultSecurityPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CometDMessagesAfterFailedHandshakeTest extends AbstractCometDTest {
    @Before
    public void init() {
        bayeuxServer.setSecurityPolicy(new Policy());
    }

    @Test
    public void testSubscribeAfterFailedHandshake() throws Exception {
        defineClass(Latch.class);

        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = get("subscribeLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/handshake', function(message)" +
                "{" +
                "    if (!message.successful)" +
                "    {" +
                "        cometd.subscribe('/foo', function(m) {});" +
                "        handshakeLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.addListener('/meta/subscribe', function(message)" +
                "{" +
                "    if (!message.successful)" +
                "    {" +
                "        subscribeLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");

        Assert.assertTrue(handshakeLatch.await(5000));
        Assert.assertTrue(subscribeLatch.await(5000));

        evaluateScript("cometd.disconnect(true);");
    }

    @Test
    public void testPublishAfterFailedHandshake() throws Exception {
        defineClass(Latch.class);

        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = get("publishLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/handshake', function(message)" +
                "{" +
                "    if (!message.successful)" +
                "    {" +
                "        cometd.publish('/foo', {});" +
                "        handshakeLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.addListener('/meta/publish', function(message)" +
                "{" +
                "    if (!message.successful)" +
                "    {" +
                "        publishLatch.countDown();" +
                "    }" +
                "});" +
                "cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");

        Assert.assertTrue(handshakeLatch.await(5000));
        Assert.assertTrue(publishLatch.await(5000));

        evaluateScript("cometd.disconnect(true);");
    }

    private class Policy extends DefaultSecurityPolicy {
        @Override
        public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
            return false;
        }
    }
}
