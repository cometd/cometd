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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CometDCallbackPollingTest extends AbstractCometDCallbackPollingTest {
    @Test
    public void testCallbackPolling() throws Exception {
        // Make the CometD URL different to simulate the cross domain request.
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                const handshakeLatch = new Latch(1);
                const connectLatch = new Latch(1);
                cometd.addListener('/meta/handshake', () => handshakeLatch.countDown());
                cometd.addListener('/meta/connect', () => connectLatch.countDown());
                cometd.handshake();
                """.replace("$U", url).replace("$L", getLogLevel()));
        Latch handshakeLatch = javaScript.get("handshakeLatch");
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertTrue(handshakeLatch.await(5000));
        Assertions.assertTrue(connectLatch.await(5000));

        evaluateScript("""
                const subscribeLatch = new Latch(1);
                const messageLatch = new Latch(1);
                cometd.addListener('/meta/subscribe', () => subscribeLatch.countDown());
                const subscription = cometd.subscribe('/test', () => messageLatch.countDown());
                """);
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        Assertions.assertTrue(subscribeLatch.await(5000));

        evaluateScript("cometd.publish('/test', {});");
        Latch messageLatch = javaScript.get("messageLatch");
        Assertions.assertTrue(messageLatch.await(5000));

        evaluateScript("""
                const unsubscribeLatch = new Latch(1);
                cometd.addListener('/meta/unsubscribe', () => unsubscribeLatch.countDown());
                cometd.unsubscribe(subscription);
                """);
        Latch unsubscribeLatch = javaScript.get("unsubscribeLatch");
        Assertions.assertTrue(unsubscribeLatch.await(5000));

        disconnect();
    }

    @Test
    public void testURLMaxLengthOneTooBigMessage() throws Exception {
        // Make the CometD URL different to simulate the cross domain request.
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("""
                const connectLatch = new Latch(1);
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', () => connectLatch.countDown());
                cometd.handshake();
                """.replace("$U", url).replace("$L", getLogLevel()));
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertTrue(connectLatch.await(5000));

        evaluateScript("""
                const publishLatch = new Latch(1);
                let data = '';
                for (let i = 0; i < 2000; ++i) {
                    data += 'x';
                }
                cometd.addListener('/meta/publish', message => {
                    if (!message.successful) {
                        publishLatch.countDown();
                    }
                });
                cometd.publish('/foo', data);
                """);
        Latch publishLatch = javaScript.get("publishLatch");
        Assertions.assertTrue(publishLatch.await(5000));

        disconnect();
    }

    @Test
    public void testURLMaxLengthThreeMessagesBatchedOneTooBigFailsWholeBatch() throws Exception {
        // Make the CometD URL different to simulate the cross domain request.
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("""
                const connectLatch = new Latch(1);
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', () => connectLatch.countDown());
                cometd.handshake();
                """.replace("$U", url).replace("$L", getLogLevel()));
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertTrue(connectLatch.await(5000));

        evaluateScript("""
                const publishLatch = new Latch(3);
                let data = '';
                for (let i = 0; i < 500; ++i) {
                    data += 'x';
                }
                cometd.addListener('/meta/publish', message => {
                    if (!message.successful) {
                        publishLatch.countDown();
                    }
                });
                cometd.batch(() => {
                    cometd.publish('/foo', data);
                    cometd.publish('/foo', data);
                    cometd.publish('/foo', data + data + data + data);
                });
                """);
        Latch publishLatch = javaScript.get("publishLatch");
        Assertions.assertTrue(publishLatch.await(5000));

        disconnect();
    }

    @Test
    public void testURLMaxLengthThreeMessagesBatchedAreSplit() throws Exception {
        // Make the CometD URL different to simulate the cross domain request.
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("""
                const connectLatch = new Latch(1);
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', () => connectLatch.countDown());
                cometd.handshake();
                """.replace("$U", url).replace("$L", getLogLevel()));
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertTrue(connectLatch.await(5000));

        evaluateScript("""
                const publishLatch = new Latch(3);
                let data = '';
                for (let i = 0; i < 500; ++i) {
                    data += 'x';
                }
                cometd.addListener('/meta/publish', message => {
                    if (message.successful) {
                        publishLatch.countDown();
                    }
                });
                cometd.batch(() => {
                    cometd.publish('/foo', data);
                    cometd.publish('/foo', data);
                    cometd.publish('/foo', data + data);
                });
                """);
        Latch publishLatch = javaScript.get("publishLatch");
        Assertions.assertTrue(publishLatch.await(5000));

        disconnect();
    }

    @Test
    public void testURLMaxLengthThreeMessagesBatchedAreSplitOrderIsKept() throws Exception {
        // Make the CometD URL different to simulate the cross domain request.
        String url = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("""
                const connectLatch = new Latch(1);
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', () => connectLatch.countDown());
                cometd.handshake();
                """.replace("$U", url).replace("$L", getLogLevel()));
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertTrue(connectLatch.await(5000));


        evaluateScript("""
                const subscribeLatch = new Latch(1);
                const publishLatch = new Latch(12);
                const channel = '/foo';
                const orders = [];
                cometd.addListener('/meta/subscribe', () => subscribeLatch.countDown());
                cometd.subscribe(channel, message => {
                    orders.push(message.order);
                    publishLatch.countDown();
                });
                """);
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        Assertions.assertTrue(subscribeLatch.await(5000));

        evaluateScript("""
                let data = '';
                for (let i = 0; i < 500; ++i) {
                    data += 'x';
                }
                cometd.addListener('/meta/publish', message => {
                    if (message.successful) {
                        publishLatch.countDown();
                    }
                });
                cometd.batch(() => {
                    cometd.publish(channel, data, {order:1});
                    cometd.publish(channel, data, {order:2});
                    cometd.publish(channel, data + data + data, {order:3});
                    cometd.publish(channel, data, {order:4});
                    cometd.publish(channel, data, {order:5});
                });
                // This additional publish must be sent after the split batch.
                cometd.publish(channel, data, {order:6});
                """);
        Latch publishLatch = javaScript.get("publishLatch");
        Assertions.assertTrue(publishLatch.await(5000));

        evaluateScript("window.assert([1,2,3,4,5,6].join(',') === orders.join(','), 'Order not respected ' + orders.join(','));");

        disconnect();
    }
}
