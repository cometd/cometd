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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.javascript.jquery.JQueryTestProvider;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class CometDInitDisconnectTest extends AbstractCometDTest {
    @Test
    public void testInitDisconnect() throws Exception {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var latch = new Latch(2);");
        Latch latch = get("latch");
        String script = "cometd.addListener('/**', function(message) { window.console.info(message.channel); latch.countDown(); });" +
                // Expect 2 messages: handshake and connect
                "cometd.handshake();";
        evaluateScript(script);
        Assert.assertTrue(latch.await(5000));

        // Wait for the long poll to happen, so that we're sure
        // the disconnect is sent after the long poll
        Thread.sleep(1000);

        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("connected", status);

        // Expect disconnect and connect
        latch.reset(2);
        evaluateScript("cometd.disconnect(true);");
        Assert.assertTrue(latch.await(5000));

        status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);

        // Make sure there are no attempts to reconnect
        latch.reset(1);
        Assert.assertFalse(latch.await(metaConnectPeriod * 3));
    }

    @Test
    public void testHandshakeDisconnect() throws Exception {
        // Dojo has a bug where aborting an XHR from the
        // handshake listener does not notify the XHR error
        // handlers, so the disconnect listener is not invoked.
        Assume.assumeTrue(System.getProperty("toolkitTestProvider").equalsIgnoreCase(JQueryTestProvider.class.getName()));

        final CountDownLatch removeLatch = new CountDownLatch(1);
        bayeuxServer.addExtension(new BayeuxServer.Extension.Adapter() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    to.addListener(new ServerSession.RemoveListener() {
                        @Override
                        public void removed(ServerSession session, boolean timeout) {
                            removeLatch.countDown();
                        }
                    });
                }
                return true;
            }
        });

        // Note that doing:
        //
        // cometd.handshake();
        // cometd.disconnect();
        //
        // will not work, since the disconnect will need to pass to the server
        // a clientId, which is not known since the handshake has not returned yet

        defineClass(Latch.class);
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/handshake', function(message)" +
                "{" +
                "    if (message.successful)" +
                "        cometd.disconnect();" +
                "});" +
                "cometd.addListener('/meta/disconnect', disconnectLatch, 'countDown');" +
                "cometd.handshake();");
        Assert.assertTrue(removeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(disconnectLatch.await(5000));
    }
}
