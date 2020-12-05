/*
 * Copyright (c) 2008-2020 the original author or authors.
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
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDDeliverTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testDeliver(String transport) throws Exception {
        initCometDServer(transport);

        new DeliverService(bayeuxServer);

        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("var pushLatch = new Latch(1);");
        Latch pushLatch = javaScript.get("pushLatch");
        evaluateScript("var _data;");
        evaluateScript("cometd.addListener('/service/deliver', function(message) { _data = message.data; pushLatch.countDown(); });");

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("cometd.handshake();");
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("var latch = new Latch(1);");
        Latch latch = javaScript.get("latch");
        evaluateScript("var listener = cometd.addListener('/meta/publish', function(message) { latch.countDown(); });");
        evaluateScript("cometd.publish('/service/deliver', { deliver: false });");
        Assertions.assertTrue(latch.await(5000));
        Assertions.assertFalse(pushLatch.await(1000));
        evaluateScript("cometd.removeListener(listener);");
        evaluateScript("window.assert(_data === undefined);");

        latch.reset(1);
        evaluateScript("listener = cometd.addListener('/meta/publish', function(message) { latch.countDown(); });");
        evaluateScript("cometd.publish('/service/deliver', { deliver: true });");
        Assertions.assertTrue(latch.await(5000));
        evaluateScript("cometd.removeListener(listener);");
        // Wait for the listener to be notified from the server
        Assertions.assertTrue(pushLatch.await(5000));
        evaluateScript("window.assert(_data !== undefined);");

        disconnect();
    }

    public static class DeliverService extends AbstractService {
        public DeliverService(BayeuxServerImpl bayeux) {
            super(bayeux, "deliver");
            addService("/service/deliver", "deliver");
        }

        public void deliver(ServerSession remote, ServerMessage message) {
            Map<String, Object> data = message.getDataAsMap();
            Boolean deliver = (Boolean)data.get("deliver");
            if (deliver) {
                data.put("echo", true);
                remote.deliver(getServerSession(), message.getChannel(), data, Promise.noop());
            }
        }
    }
}
