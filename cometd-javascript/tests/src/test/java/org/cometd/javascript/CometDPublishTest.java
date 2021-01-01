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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDPublishTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testPublish(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function() { readyLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'})");
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("var echoLatch = new Latch(1);");
        Latch echoLatch = javaScript.get("echoLatch");
        evaluateScript("var subscription = cometd.subscribe('/echo', function() { echoLatch.countDown(); });");
        evaluateScript("var publishLatch = new Latch(1);");
        Latch publishLatch = javaScript.get("publishLatch");
        evaluateScript("cometd.addListener('/meta/publish', function() { publishLatch.countDown(); });");

        evaluateScript("cometd.publish('/echo', 'test');");
        Assertions.assertTrue(echoLatch.await(5000));
        Assertions.assertTrue(publishLatch.await(5000));

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testPublishSuccessfulInvokesCallback(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function() { readyLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'})");
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("var publishLatch = new Latch(2);");
        Latch publishLatch = javaScript.get("publishLatch");
        evaluateScript("cometd.addListener('/meta/publish', function() { publishLatch.countDown(); });");

        evaluateScript("cometd.publish('/echo', 'test1', function() { publishLatch.countDown(); });");
        Assertions.assertTrue(publishLatch.await(5000));

        // Be sure that another publish without callback does not trigger the previous callback
        publishLatch.reset(2);
        evaluateScript("cometd.publish('/echo', 'test2');");
        Assertions.assertFalse(publishLatch.await(1000));
        Assertions.assertEquals(1, publishLatch.getCount());

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testPublishFailedInvokesCallback(String transport) throws Exception {
        initCometDServer(transport);

        bayeuxServer.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean rcv(ServerSession from, ServerMessage.Mutable message) {
                return !"/echo".equals(message.getChannel());
            }
        });

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function() { readyLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'})");
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("var publishLatch = new Latch(2);");
        Latch publishLatch = javaScript.get("publishLatch");
        evaluateScript("cometd.addListener('/meta/publish', function() { publishLatch.countDown(); });");

        evaluateScript("cometd.publish('/echo', 'test1', function(message) {" +
                "    if (!message.successful) {" +
                "        publishLatch.countDown();" +
                "    }" +
                "});");
        Assertions.assertTrue(publishLatch.await(5000));

        // Be sure that another publish without callback does not trigger the previous callback
        publishLatch.reset(2);
        evaluateScript("cometd.publish('/echo', 'test2');");
        Assertions.assertFalse(publishLatch.await(1000));
        Assertions.assertEquals(1, publishLatch.getCount());

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testPublishWithServerDownInvokesCallback(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) {" +
                "    if (message.successful) {" +
                "        readyLatch.countDown();" +
                "    } " +
                "});");
        evaluateScript("var failedLatch = new Latch(2);");
        Latch failedLatch = javaScript.get("failedLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) {" +
                "    if (!message.successful) {" +
                "        failedLatch.countDown();" +
                "    } " +
                "});");
        evaluateScript("var publishLatch = new Latch(2);");
        Latch publishLatch = javaScript.get("publishLatch");
        evaluateScript("cometd.addListener('/meta/publish', function() { publishLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'})");
        Assertions.assertTrue(readyLatch.await(5000));

        // Wait for the /meta/connect to be held by the server.
        Thread.sleep(1000);

        server.stop();

        Assertions.assertTrue(failedLatch.await(5000));

        evaluateScript("cometd.publish('/echo', 'test2', function(message) {" +
                "    if (!message.successful) {" +
                "        publishLatch.countDown();" +
                "    }" +
                "});");
        Assertions.assertTrue(publishLatch.await(5000));

        disconnect();
    }
}
