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
package org.cometd.javascript.extension;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.javascript.AbstractCometDTransportsTest;
import org.cometd.javascript.Latch;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class CometDReloadExtensionTest extends AbstractCometDTransportsTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testReloadWithConfiguration(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("const readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        String attributeName = "reload.test";
        provideReloadExtension();
        evaluateScript("""
                cometd.unregisterExtension('reload');
                cometd.registerExtension('reload', new cometdModule.ReloadExtension({
                    name: '$A'
                }));

                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', message => {
                   if (message.successful) {
                       readyLatch.countDown();
                   }
                });
                """.replace("$U", cometdURL).replace("$L", getLogLevel()).replace("$A", attributeName));
        evaluateScript("cometd.handshake();");
        Assertions.assertTrue(readyLatch.await(5000));

        // Wait that the long poll is established before reloading
        Thread.sleep(metaConnectPeriod / 2);

        evaluateScript("cometd.reload();");
        String reloadState = evaluateScript("window.sessionStorage.getItem('$A');".replace("$A", attributeName));
        Assertions.assertNotNull(reloadState);

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testReloadedHandshakeContainsExtension(String transport) throws Exception {
        initCometDServer(transport);

        bayeuxServer.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    message.getExt(true).put("foo", true);
                }
                return true;
            }
        });

        evaluateScript("const readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        provideReloadExtension();
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', message => {
                   if (message.successful) {
                       readyLatch.countDown();
                   }
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        // Wait that the long poll is established before reloading
        Thread.sleep(metaConnectPeriod / 2);

        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage(transport);

        evaluateScript("const readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        provideReloadExtension();
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                let ext;
                cometd.addListener('/meta/handshake', message => {
                   if (message.successful) {
                      ext = message.ext;
                   }
                });
                cometd.addListener('/meta/connect', message => {
                   if (message.successful) {
                       readyLatch.countDown();
                   }
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        evaluateScript("""
                window.assert(ext !== undefined, 'ext must be present');
                window.assert(ext.reload === true, 'ext.reload must be true: ' + JSON.stringify(ext));
                window.assert(ext.foo === true, 'ext.foo must be true: ' + JSON.stringify(ext));
                """);

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testReloadDoesNotExpire(String transport) throws Exception {
        initCometDServer(transport);

        provideReloadExtension();
        evaluateScript("const readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', message => {
                   if (message.successful) {
                       readyLatch.countDown();
                   }
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Wait that the long poll is established before reloading
        Thread.sleep(metaConnectPeriod / 2);

        // Calling reload() results in the state being saved.
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage(transport);

        provideReloadExtension();
        evaluateScript("const readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        evaluateScript("const expireLatch = new Latch(1);");
        Latch expireLatch = javaScript.get("expireLatch");
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', message => {
                   if (message.successful) {
                       readyLatch.countDown();
                   } else {
                       expireLatch.countDown();
                   }
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assertions.assertEquals(clientId, newClientId);

        // Make sure that reloading will not expire the client on the server
        Assertions.assertFalse(expireLatch.await(expirationPeriod + metaConnectPeriod));

        disconnect();
    }

    @Test
    public void testReloadWithWebSocketTransport() throws Exception {
        initCometDServer(null);
        testReloadWithTransport(cometdURL, "websocket");
    }

    @Test
    public void testReloadWithLongPollingTransport() throws Exception {
        initCometDServer(null);
        testReloadWithTransport(cometdURL, "long-polling");
    }

    @Test
    public void testReloadWithCallbackPollingTransport() throws Exception {
        initCometDServer(null);
        // Make the CometD URL different to simulate the cross domain request
        String url = cometdURL.replace("localhost", "127.0.0.1");
        testReloadWithTransport(url, "callback-polling");
    }

    private void testReloadWithTransport(String url, String transportName) throws Exception {
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.unregisterTransports();
                cometd.registerTransport('$T', originalTransports['$T']);
                """.replace("$U", cometdURL).replace("$L", getLogLevel()).replace("$T", transportName));

        evaluateScript("const readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        provideReloadExtension();
        evaluateScript("""
                cometd.addListener('/meta/connect', message => {
                   if (message.successful) {
                       readyLatch.countDown();
                   }
                });
                cometd.handshake();
                """);
        Assertions.assertTrue(readyLatch.await(5000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Calling reload() results in the state being saved.
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage(null);

        provideReloadExtension();
        // Leave the default transports so that we can test if the previous transport is the one used on reload
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.registerTransport('$T', originalTransports['$T']);
                """.replace("$U", cometdURL).replace("$L", getLogLevel()).replace("$T", transportName));

        evaluateScript("const readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        evaluateScript("""
                cometd.addListener('/meta/connect', message => {
                   if (message.successful) {
                       readyLatch.countDown();
                   }
                });
                cometd.handshake();
                """);
        Assertions.assertTrue(readyLatch.await(5000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assertions.assertEquals(clientId, newClientId);

        String transportType = evaluateScript("cometd.getTransport().getType();");
        Assertions.assertEquals(transportName, transportType);

        evaluateScript("cometd.disconnect();");
        Thread.sleep(1000);

        // Be sure the sessionStorage item has been removed on disconnect.
        Boolean reloadState = evaluateScript("window.sessionStorage.getItem('org.cometd.reload') == null;");
        Assertions.assertTrue(reloadState);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testReloadAcrossServerRestart(String transport) throws Exception {
        initCometDServer(transport);

        provideReloadExtension();

        evaluateScript("const readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("const stopLatch = new Latch(1);");
        Latch stopLatch = javaScript.get("stopLatch");
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.addListener('/meta/connect', message => {
                    if (message.successful) {
                        readyLatch.countDown();
                    } else {
                        stopLatch.countDown();
                    }
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        // Stop the server
        int port = connector.getLocalPort();
        server.stop();
        Assertions.assertTrue(stopLatch.await(5000));

        // Disconnect
        evaluateScript("cometd.disconnect();");

        // Restart the server
        connector.setPort(port);
        server.start();

        // Reload the page
        evaluateScript("cometd.reload();");
        destroyPage();
        initPage(transport);

        provideReloadExtension();
        evaluateScript("");

        evaluateScript("const readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                let failures = 0;
                cometd.addListener('/meta/connect', message => {
                    if (message.successful) {
                        readyLatch.countDown();
                    } else {
                        ++failures;
                    }
                });
                cometd.handshake();
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));
        // Must not have failed with a 402::unknown_session error.
        Assertions.assertEquals(0, ((Number)javaScript.get("failures")).intValue());

        disconnect();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testReloadWithSubscriptionAndPublish(String transport) throws Exception {
        initCometDServer(transport);

        evaluateApplication();
        Latch latch = javaScript.get("latch");
        Assertions.assertTrue(latch.await(5000));

        // Calling reload() results in the state being saved.
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage(transport);

        evaluateApplication();
        latch = javaScript.get("latch");
        Assertions.assertTrue(latch.await(5000));

        evaluateScript("""
                // Check that handshake was faked.
                window.assert(extHandshake === null, 'extHandshake');
                window.assert(rcvHandshake !== null, 'rcvHandshake');
                // Check that subscription went out.
                window.assert(extSubscribe !== null, 'extSubscribe');
                window.assert(rcvSubscribe === null, 'rcvSubscribe');
                // Check that publish went out.
                window.assert(extPublish !== null, 'extPublish');
                """);

        disconnect();
    }

    private void evaluateApplication() {
        evaluateScript("""
                const latch = new Latch(1);
                                
                cometd.configure({url: '$U', logLevel: '$L'});

                let extHandshake = null;
                let extSubscribe = null;
                let extPublish = null;
                cometd.registerExtension('test', {
                   outgoing: message => {
                       if (message.channel === '/meta/handshake') {
                           extHandshake = message;
                       } else if (message.channel === '/meta/subscribe') {
                           extSubscribe = message;
                       } else if (!/^\\/meta\\//.test(message.channel)) {
                           extPublish = message;
                       }
                   }
                });
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        provideReloadExtension();
        evaluateScript("""
                // Override receive() since it's the method called by the extension to fake responses.
                let rcvHandshake = null;
                let rcvSubscribe = null;
                const _receive = cometd.receive;
                cometd.receive = message => {
                   cometd._debug('Received message', JSON.stringify(message));
                   _receive(message);
                   if (message.channel === '/meta/handshake') {
                       rcvHandshake = message;
                   } else if (message.channel === '/meta/subscribe') {
                       rcvSubscribe = message;
                   }
                };

                let _connected = false;
                function _init(message) {
                   const wasConnected = _connected;
                   _connected = message.successful;
                   if (!wasConnected && _connected) {
                       cometd.batch(() => {
                           cometd.subscribe('/foo', () => latch.countDown());
                           cometd.publish('/foo', 'foo_data');
                       });
                   }
                }

                cometd.addListener('/meta/connect', _init);
                cometd.handshake();
                """);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testReloadWithHandshakeCallback(String transport) throws Exception {
        initCometDServer(transport);

        evaluateScript("const readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");

        provideReloadExtension();
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.handshake(message => {
                    if (message.successful) {
                        readyLatch.countDown();
                    }
                });
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        // Wait that the long poll is established before reloading
        Thread.sleep(metaConnectPeriod / 2);

        // Reload the page
        evaluateScript("cometd.reload();");
        destroyPage();
        initPage(transport);

        evaluateScript("const readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");

        provideReloadExtension();
        evaluateScript("""
                cometd.configure({url: '$U', logLevel: '$L'});
                cometd.handshake(message => {
                    if (message.successful) {
                        readyLatch.countDown();
                    }
                });
                """.replace("$U", cometdURL).replace("$L", getLogLevel()));
        Assertions.assertTrue(readyLatch.await(5000));

        disconnect();
    }
}
