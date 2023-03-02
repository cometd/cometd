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

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.DefaultSecurityPolicy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDSubscribeTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testSubscriptionsUnsubscriptionsForSameChannelOnlySentOnce(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("""
                const subscribeLatch = new Latch(1);
                cometd.addListener('/meta/subscribe', () => subscribeLatch.countDown());
                const unsubscribeLatch = new Latch(1);
                cometd.addListener('/meta/unsubscribe', () => unsubscribeLatch.countDown());
                cometd.init({url: '$U', logLevel: '$L'});
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Thread.sleep(1000); // Wait for long poll.

        evaluateScript("const subscription = cometd.subscribe('/foo', () => {});");
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        Assertions.assertTrue(subscribeLatch.await(5000));

        evaluateScript("cometd.unsubscribe(subscription);");
        Latch unsubscribeLatch = javaScript.get("unsubscribeLatch");
        Assertions.assertTrue(unsubscribeLatch.await(5000));

        // Two subscriptions to the same channel generate only one message to the server.
        subscribeLatch.reset(2);
        evaluateScript("""
                const callbackLatch = new Latch(2);
                const subscription1 = cometd.subscribe('/foo', () => {}, () => callbackLatch.countDown());
                const subscription2 = cometd.subscribe('/foo', () => {}, () => callbackLatch.countDown());
                """);
        // The callback should be notified even if the message was not sent to the server.
        Latch callbackLatch = javaScript.get("callbackLatch");
        Assertions.assertTrue(callbackLatch.await(5000));
        Assertions.assertFalse(subscribeLatch.await(1000));

        // No message sent to server if there still are subscriptions.
        unsubscribeLatch.reset(1);
        callbackLatch.reset(1);
        evaluateScript("cometd.unsubscribe(subscription2, () => callbackLatch.countDown());");
        // The callback should be notified even if the message was not sent to the server.
        Assertions.assertTrue(callbackLatch.await(5000));
        Assertions.assertFalse(unsubscribeLatch.await(1000));

        // Expect message sent to the server for last unsubscription on the channel.
        unsubscribeLatch.reset(1);
        callbackLatch.reset(1);
        evaluateScript("cometd.unsubscribe(subscription1, () => callbackLatch.countDown());");
        Assertions.assertTrue(callbackLatch.await(5000));
        Assertions.assertTrue(unsubscribeLatch.await(5000));

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSubscriptionsRemovedOnReHandshake(String transport) throws Exception {
        initCometDServer(transport);

        // Listeners are not removed in case of re-handshake
        // since they are not dependent on the clientId.
        evaluateScript("""
                const latch = new Latch(1);
                cometd.addListener('/meta/publish', () => latch.countDown());
                cometd.init({url: '$U', logLevel: '$L'});
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Thread.sleep(1000); // Wait for long poll

        disconnect();
        // Wait for the /meta/connect to return.
        Thread.sleep(1000);

        // Reconnect again.
        evaluateScript("cometd.handshake();");
        Thread.sleep(1000); // Wait for long poll

        // Wait for the message on the listener.
        evaluateScript("cometd.publish('/foo', {});");
        Latch latch = javaScript.get("latch");
        Assertions.assertTrue(latch.await(5000));

        latch.reset(1);
        evaluateScript("""
                const subscriber = new Latch(1);
                cometd.subscribe('/test', () => subscriber.countDown());
                cometd.publish('/test', {});
                """);
        // Wait for the message on the subscriber and on the listener.
        Assertions.assertTrue(latch.await(5000));
        Latch subscriber = javaScript.get("subscriber");
        Assertions.assertTrue(subscriber.await(5000));

        disconnect();
        // Wait for the /meta/connect to return.
        Thread.sleep(1000);

        // Reconnect again.
        evaluateScript("cometd.handshake();");
        Thread.sleep(1000); // Wait for long poll

        // Now the previous subscriber must be gone, but not the listener.
        // Subscribe again: if the previous listener is not gone, I get 2 notifications.
        latch.reset(1);
        subscriber.reset(2);
        evaluateScript("""
                cometd.subscribe('/test', () => subscriber.countDown());
                cometd.publish('/test', {});
                """);
        Assertions.assertTrue(latch.await(5000));
        Assertions.assertFalse(subscriber.await(5000));

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDynamicResubscription(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("""
                const latch = new Latch(1);
                cometd.configure({url: '$U', logLevel: '$L'});
                let _subscription;
                cometd.addListener('/meta/handshake', m => {
                    if (m.successful) {
                        cometd.batch(() => {
                            cometd.subscribe('/static', () => latch.countDown());
                            if (_subscription) {
                                _subscription = cometd.resubscribe(_subscription);
                            }
                        });
                    }
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        // Wait for /meta/connect
        Thread.sleep(1000);

        evaluateScript("cometd.publish('/static', {});");
        Latch latch = javaScript.get("latch");
        Assertions.assertTrue(latch.await(5000));
        latch.reset(2);

        evaluateScript("""
                cometd.batch(() => {
                    _subscription = cometd.subscribe('/dynamic', () => latch.countDown());
                    cometd.publish('/static', {});
                    cometd.publish('/dynamic', {});
                });
                """);

        Assertions.assertTrue(latch.await(5000));

        stopServer();

        evaluateScript("""
                const connectLatch = new Latch(1);
                cometd.addListener('/meta/connect', m => {
                    if (m.successful) {
                        connectLatch.countDown();
                    }
                });
                """);

        // Restart the server to trigger a re-handshake.
        prepareAndStartServer(new HashMap<>());

        // Wait until we are fully reconnected.
        Latch connectLatch = javaScript.get("connectLatch");
        Assertions.assertTrue(connectLatch.await(5000));

        latch.reset(2);
        evaluateScript("""
                cometd.batch(() => {
                    cometd.publish('/static', {});
                    cometd.publish('/dynamic', {});
                });
                """);

        Assertions.assertTrue(latch.await(5000));

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSubscriptionDeniedRemovesListener(String transport) throws Exception {
        initCometDServer(transport);

        AtomicBoolean subscriptionAllowed = new AtomicBoolean(false);
        bayeuxServer.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
                return subscriptionAllowed.get();
            }
        });

        evaluateScript("""
                let subscriptionAllowed = false;
                const subscribeLatch = new Latch(1);
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/subscribe', m => {
                    // Either both false or both true should count down the latch.
                    if (subscriptionAllowed ^ !m.successful) {
                        subscribeLatch.countDown();
                    }
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        // Wait for /meta/connect
        Thread.sleep(1000);

        String sessionId = evaluateScript("cometd.getClientId();");

        String channelName = "/test";
        evaluateScript("""
                const messageLatch = new Latch(1);
                cometd.subscribe('$C', () => messageLatch.countDown());
                """.replace("$C", channelName));
        Latch subscribeLatch = javaScript.get("subscribeLatch");
        Assertions.assertTrue(subscribeLatch.await(5000));

        // Verify that messages are not received
        bayeuxServer.getSession(sessionId).deliver(null, channelName, "data", Promise.noop());
        Latch messageLatch = javaScript.get("messageLatch");
        Assertions.assertFalse(messageLatch.await(1000));

        // Reset and allow subscriptions
        subscribeLatch.reset(1);
        messageLatch.reset(1);
        subscriptionAllowed.set(true);
        evaluateScript("""
                subscriptionAllowed = true;
                cometd.subscribe('$C', () => messageLatch.countDown());
                """.replace("$C", channelName));
        Assertions.assertTrue(subscribeLatch.await(5000));

        // Verify that messages are received
        bayeuxServer.getChannel(channelName).publish(null, "data", Promise.noop());
        Assertions.assertTrue(messageLatch.await(1000));

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSubscriptionSuccessfulInvokesCallback(String transport) throws Exception {
        initCometDServer(transport);

        String channelName = "/foo";
        evaluateScript("""
                const latch = new Latch(2);
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/handshake', () => {
                    const subscription = cometd.subscribe('$C', () => {}, message => {
                        latch.countDown();
                        cometd.unsubscribe(subscription, message => {
                            latch.countDown();
                        });
                    });
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()).replace("$C", channelName));

        Latch latch = javaScript.get("latch");
        Assertions.assertTrue(latch.await(5000));

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSubscriptionDeniedInvokesCallback(String transport) throws Exception {
        initCometDServer(transport);

        String channelName = "/foo";
        bayeuxServer.setSecurityPolicy(new DefaultSecurityPolicy() {
            @Override
            public boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
                if (channelName.equals(channel.getId())) {
                    return false;
                }
                return super.canSubscribe(server, session, channel, message);
            }
        });

        evaluateScript("""
                const subscribeLatch = new Latch(1);
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.handshake(() => {
                    cometd.subscribe('$C', () => {}, {}, message => {
                        subscribeLatch.countDown();
                    });
                });
                """.replace("$U", cometdURL).replace("$L", getLogLevel()).replace("$C", channelName));

        Latch subscribeLatch = javaScript.get("subscribeLatch");
        Assertions.assertTrue(subscribeLatch.await(5000));

        disconnect();
    }
}
