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

import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.Assert;
import org.junit.Test;

public class CometDTransportFailureTest extends AbstractCometDWebSocketTest {
    @Test
    public void testConnectFailureChangeURL() throws Exception {
        ServerConnector connector2 = new ServerConnector(server);
        connector2.start();
        server.addConnector(connector2);
        // Fail the second connect.
        bayeuxServer.addExtension(new ConnectFailureExtension() {
            @Override
            protected boolean onConnect(int count) throws Exception {
                if (count != 2) {
                    return true;
                }
                connector.stop();
                return false;
            }
        });

        defineClass(Latch.class);

        evaluateScript("" +
                "cometd.configure({" +
                "    url: '" + cometdURL + "', " +
                "    logLevel: '" + getLogLevel() + "'" +
                "});");

        String newURL = "http://localhost:" + connector2.getLocalPort() + context.getContextPath() + cometdServletPath;

        // Replace the transport failure logic.
        evaluateScript("" +
                "var oTF = cometd.onTransportFailure;" +
                "cometd.onTransportFailure = function(message, failureInfo, failureHandler) {" +
                "    if (message.channel === '/meta/connect') {" +
                "        failureInfo.action = 'retry';" +
                "        failureInfo.delay = 0;" +
                "        failureInfo.url = '" + newURL + "';" +
                "        failureHandler(failureInfo);" +
                "        /* Reinstall the original function */" +
                "        cometd.onTransportFailure = oTF;" +
                "    }" +
                "    else {" +
                "        oTF.call(this, message, failureInfo, failureHandler);" +
                "    }" +
                "};");

        // The second connect fails, the third connect should succeed on the new URL.
        evaluateScript("var latch = new Latch(1);");
        Latch latch = get("latch");
        evaluateScript("" +
                "var url = null;" +
                "var connects = 0;" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "    ++connects;" +
                "    if (connects === 3 && message.successful) {" +
                "        url = cometd.getTransport().getURL();" +
                "        latch.countDown();" +
                "    }" +
                "});" +
                "cometd.handshake();");

        Assert.assertTrue(latch.await(5000));
        Assert.assertEquals(newURL, get("url"));

        connector2.stop();
    }

    @Test
    public void testConnectFailureChangeTransport() throws Exception {
        bayeuxServer.addExtension(new ConnectFailureExtension() {
            @Override
            protected boolean onConnect(int count) throws Exception {
                return count != 2;
            }
        });

        defineClass(Latch.class);

        evaluateScript("" +
                "cometd.configure({" +
                "    url: '" + cometdURL + "', " +
                "    logLevel: '" + getLogLevel() + "'" +
                "});" +
                "/* Disable the websocket transport */" +
                "cometd.websocketEnabled = false;" +
                "/* Add the long-polling transport */" +
                "cometd.registerTransport('long-polling', originalTransports['long-polling']);");

        // Replace the transport failure logic.
        evaluateScript("" +
                "var oTF = cometd.onTransportFailure;" +
                "cometd.onTransportFailure = function(message, failureInfo, failureHandler) {" +
                "    if (message.channel === '/meta/connect') {" +
                "        failureInfo.action = 'retry';" +
                "        failureInfo.delay = 0;" +
                "        failureInfo.transport = cometd.findTransport('websocket');" +
                "        failureHandler(failureInfo);" +
                "        /* Reinstall the original function */" +
                "        cometd.onTransportFailure = oTF;" +
                "    }" +
                "    else {" +
                "        oTF.call(this, message, failureInfo, failureHandler);" +
                "    }" +
                "};");

        evaluateScript("var latch = new Latch(1);");
        Latch latch = get("latch");
        evaluateScript("" +
                "var transport = null;" +
                "var connects = 0;" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "    ++connects;" +
                "    if (connects === 3 && message.successful) {" +
                "        transport = cometd.getTransport().getType();" +
                "        latch.countDown();" +
                "    }" +
                "});" +
                "cometd.handshake();");

        Assert.assertTrue(latch.await(5000));
        Assert.assertEquals("websocket", get("transport"));
    }

    private abstract class ConnectFailureExtension extends BayeuxServer.Extension.Adapter {
        private final AtomicInteger connects = new AtomicInteger();

        @Override
        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message) {
            if (Channel.META_CONNECT.equals(message.getChannel())) {
                try {
                    return onConnect(connects.incrementAndGet());
                } catch (Exception x) {
                    throw new RuntimeException(x);
                }
            }
            return true;
        }

        protected abstract boolean onConnect(int count) throws Exception;
    }
}
