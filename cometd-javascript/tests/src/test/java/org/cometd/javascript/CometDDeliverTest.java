/*
 * Copyright (c) 2008-2022 the original author or authors.
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
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDDeliverTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testDeliver(String transport) throws Exception {
        initCometDServer(transport);

        new DeliverService(bayeuxServer);

        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                const pushLatch = new Latch(1);
                let _data;
                cometd.addListener('/service/deliver', message => { _data = message.data; pushLatch.countDown(); });
                const readyLatch = new Latch(1);
                cometd.addListener('/meta/connect', () => readyLatch.countDown());
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));

        Latch readyLatch = javaScript.get("readyLatch");
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("""
                const latch = new Latch(1);
                let listener = cometd.addListener('/meta/publish', () => latch.countDown());
                cometd.publish('/service/deliver', { deliver: false });
                """);
        Latch latch = javaScript.get("latch");
        Assertions.assertTrue(latch.await(5000));

        Latch pushLatch = javaScript.get("pushLatch");
        Assertions.assertFalse(pushLatch.await(1000));

        evaluateScript("""
                cometd.removeListener(listener);
                window.assert(_data === undefined);
                """);

        latch.reset(1);
        evaluateScript("""
                listener = cometd.addListener('/meta/publish', () => latch.countDown());
                cometd.publish('/service/deliver', { deliver: true });
                """);

        Assertions.assertTrue(latch.await(5000));

        evaluateScript("cometd.removeListener(listener);");
        // Wait for the listener to be notified from the server.
        Assertions.assertTrue(pushLatch.await(5000));
        evaluateScript("window.assert(_data !== undefined);");

        disconnect();
    }

    public static class DeliverService extends AbstractService {
        public DeliverService(BayeuxServer bayeux) {
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
