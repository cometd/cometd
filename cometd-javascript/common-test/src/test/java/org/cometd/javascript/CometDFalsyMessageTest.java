/*
 * Copyright (c) 2008-2017 the original author or authors.
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

import org.cometd.bayeux.server.ServerSession;
import org.junit.Assert;
import org.junit.Test;

public class CometDFalsyMessageTest extends AbstractCometDTest {
    @Test
    public void testEmptyStringMessage() throws Exception {
        testFalsyMessage("");
    }

    @Test
    public void testZeroMessage() throws Exception {
        testFalsyMessage(0);
    }

    private void testFalsyMessage(Object content) throws Exception {
        String channelName = "/foo";
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("var messageLatch = new Latch(1);");
        Latch messageLatch = javaScript.get("messageLatch");
        evaluateScript("cometd.addListener('/meta/handshake', function(m) {" +
                "    if (m.successful) {" +
                "        cometd.subscribe('" + channelName + "', function() { messageLatch.countDown(); });" +
                "    }" +
                "});");

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        evaluateScript("cometd.addListener('/meta/subscribe', function() { subscribeLatch.countDown(); });");

        evaluateScript("cometd.handshake();");
        Assert.assertTrue(subscribeLatch.await(5000));

        String sessionId = evaluateScript("cometd.getClientId();");
        ServerSession session = bayeuxServer.getSession(sessionId);
        session.deliver(null, channelName, content);

        Assert.assertTrue(messageLatch.await(5000));
    }
}
