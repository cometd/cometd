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
package org.cometd.javascript.extension;

import org.cometd.bayeux.Promise;
import org.cometd.javascript.AbstractCometDTransportsTest;
import org.cometd.javascript.Latch;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CometDAckAndReloadExtensionsTest extends AbstractCometDTransportsTest {
    @Before
    public void initExtensions() throws Exception {
        bayeuxServer.addExtension(new AcknowledgedMessagesExtension());
        provideMessageAcknowledgeExtension();
        provideReloadExtension();
    }

    @Test
    public void testAckAndReloadExtensions() throws Exception {
        AckService ackService = new AckService(bayeuxServer);

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function() { readyLatch.countDown(); });");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        // Send a message so that the ack counter is initialized
        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("" +
                "cometd.subscribe('/test', function() { latch.countDown(); });" +
                "cometd.publish('/test', 'message1');");
        Assert.assertTrue(latch.await(5000));

        // Wait to allow the long poll to go to the server and tell it the ack id
        Thread.sleep(1000);

        // Calling reload() results in the state being saved.
        evaluateScript("cometd.reload();");

        // Reload the page, and simulate that a message has been received meanwhile on server
        destroyPage();
        ackService.emit("message2");
        initPage();
        initExtensions();

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        // Expect 2 messages: one sent in the middle of reload, one after reload
        evaluateScript("var latch = new Latch(2);");
        latch = javaScript.get("latch");
        evaluateScript("" +
                "var testMessage = [];" +
                "cometd.addListener('/meta/handshake', function(message) {" +
                "   cometd.batch(function() {" +
                "       cometd.subscribe('/test', function(message) { testMessage.push(message); latch.countDown(); });" +
                "       cometd.subscribe('/echo', function(message) { readyLatch.countDown(); });" +
                "       cometd.publish('/echo', {});" +
                "   });" +
                "});" +
                "cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        ackService.emit("message3");
        Assert.assertTrue(latch.await(5000));

        evaluateScript("window.assert(testMessage.length === 2, 'testMessage.length');");
        evaluateScript("window.assert(testMessage[0].data == 'message2', 'message2');");
        evaluateScript("window.assert(testMessage[1].data == 'message3', 'message3');");

        disconnect();
    }

    public static class AckService extends AbstractService {
        private AckService(BayeuxServerImpl bayeux) {
            super(bayeux, "ack-test");
        }

        public void emit(String content) {
            getBayeux().getChannel("/test").publish(getServerSession(), content, Promise.noop());
        }
    }
}
