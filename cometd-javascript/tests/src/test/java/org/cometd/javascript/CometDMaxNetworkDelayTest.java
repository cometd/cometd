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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDMaxNetworkDelayTest extends AbstractCometDTransportsTest {
    private final long maxNetworkDelay = 2000;

    @ParameterizedTest
    @MethodSource("transports")
    public void testMaxNetworkDelay(String transport) throws Exception {
        initCometDServer(transport);

        bayeuxServer.addExtension(new DelayingExtension());

        evaluateScript("var Listener = Java.type('" + Listener.class.getName() + "');");
        evaluateScript("var publishListener = new Listener();");
        Listener publishListener = javaScript.get("publishListener");
        evaluateScript("cometd.addListener('/meta/publish', function(m) { publishListener.handle(m); });");
        evaluateScript("cometd.configure({" +
                "url: '" + cometdURL + "', " +
                "maxNetworkDelay: " + maxNetworkDelay + ", " +
                "logLevel: '" + getLogLevel() + "'" +
                "});");

        evaluateScript("cometd.handshake();");

        // Allow long poll to establish
        Thread.sleep(1000);

        AtomicReference<List<Throwable>> failures = new AtomicReference<>(new ArrayList<>());
        publishListener.expect(failures, 1);
        evaluateScript("cometd.publish('/test', {});");

        // The publish() above is supposed to return immediately
        // However, the test holds it for 2 * maxNetworkDelay
        // The request timeout kicks in after maxNetworkDelay,
        // canceling the request.
        Assertions.assertTrue(publishListener.await(2 * maxNetworkDelay));
        Assertions.assertTrue(failures.get().isEmpty(), failures.get().toString());

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = javaScript.get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', function() { disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assertions.assertTrue(disconnectLatch.await(5000));

        // Avoid exceptions by sleeping a while
        Thread.sleep(maxNetworkDelay);
    }

    public static class Listener {
        private AtomicReference<List<Throwable>> failures;
        private CountDownLatch latch;

        public void handle(Object jsMessage) {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>)jsMessage;
            if ((Boolean)message.get("successful")) {
                failures.get().add(new AssertionError("Publish"));
            }
            latch.countDown();
        }

        public void expect(AtomicReference<List<Throwable>> failures, int count) {
            this.failures = failures;
            this.latch = new CountDownLatch(count);
        }

        public boolean await(long timeout) throws InterruptedException {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }
    }

    private class DelayingExtension implements BayeuxServer.Extension {
        @Override
        public boolean rcv(ServerSession from, ServerMessage.Mutable message) {
            // We hold the publish longer than the maxNetworkDelay
            try {
                Thread.sleep(2 * maxNetworkDelay);
                return true;
            } catch (InterruptedException x) {
                throw new RuntimeException(x);
            }
        }
    }
}
