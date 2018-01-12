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
package org.cometd.javascript.extension;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.javascript.AbstractCometDTransportsTest;
import org.cometd.javascript.Latch;
import org.junit.Assert;
import org.junit.Test;

public class CometDReloadExtensionTest extends AbstractCometDTransportsTest {
    @Test
    public void testReloadWithConfiguration() throws Exception {
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        String attributeName = "reload.test";
        provideReloadExtension();
        evaluateScript("" +
                "cometd.unregisterExtension('reload');" +
                "cometd.registerExtension('reload', new cometdModule.ReloadExtension({" +
                "    name: '" + attributeName + "'" +
                "}));" +
                "" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "   if (message.successful) {" +
                "       readyLatch.countDown();" +
                "   }" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        // Wait that the long poll is established before reloading
        Thread.sleep(metaConnectPeriod / 2);

        evaluateScript("cometd.reload();");
        String reloadState = evaluateScript("window.sessionStorage.getItem('" + attributeName + "');");
        Assert.assertNotNull(reloadState);

        disconnect();
    }

    @Test
    public void testReloadedHandshakeContainsExtension() throws Exception {
        bayeuxServer.addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message) {
                if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    message.getExt(true).put("foo", true);
                }
                return true;
            }
        });

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        provideReloadExtension();
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "   if (message.successful) {" +
                "       readyLatch.countDown();" +
                "   }" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        // Wait that the long poll is established before reloading
        Thread.sleep(metaConnectPeriod / 2);

        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();

        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        provideReloadExtension();
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "var ext = undefined;" +
                "cometd.addListener('/meta/handshake', function(message) {" +
                "   if (message.successful) {" +
                "      ext = message.ext;" +
                "   }" +
                "});" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "   if (message.successful) {" +
                "       readyLatch.countDown();" +
                "   }" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        evaluateScript("" +
                "window.assert(ext !== undefined, 'ext must be present');" +
                "window.assert(ext.reload === true, 'ext.reload must be true: ' + JSON.stringify(ext));" +
                "window.assert(ext.foo === true, 'ext.foo must be true: ' + JSON.stringify(ext));");

        disconnect();
    }

    @Test
    public void testReloadDoesNotExpire() throws Exception {
        provideReloadExtension();
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "   if (message.successful) {" +
                "       readyLatch.countDown();" +
                "   }" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Wait that the long poll is established before reloading
        Thread.sleep(metaConnectPeriod / 2);

        // Calling reload() results in the state being saved.
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();

        provideReloadExtension();
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        evaluateScript("var expireLatch = new Latch(1);");
        Latch expireLatch = javaScript.get("expireLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "   if (message.successful) {" +
                "       readyLatch.countDown();" +
                "   } else {" +
                "       expireLatch.countDown();" +
                "   }" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assert.assertEquals(clientId, newClientId);

        // Make sure that reloading will not expire the client on the server
        Assert.assertFalse(expireLatch.await(expirationPeriod + metaConnectPeriod));

        disconnect();
    }

    @Test
    public void testReloadWithWebSocketTransport() throws Exception {
        testReloadWithTransport(cometdURL, "websocket");
    }

    @Test
    public void testReloadWithLongPollingTransport() throws Exception {
        testReloadWithTransport(cometdURL, "long-polling");
    }

    @Test
    public void testReloadWithCallbackPollingTransport() throws Exception {
        // Make the CometD URL different to simulate the cross domain request
        String url = cometdURL.replace("localhost", "127.0.0.1");
        testReloadWithTransport(url, "callback-polling");
    }

    private void testReloadWithTransport(String url, String transportName) throws Exception {
        evaluateScript("cometd.configure({url: '" + url + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("cometd.unregisterTransports();");
        evaluateScript("cometd.registerTransport('" + transportName + "', originalTransports['" + transportName + "']);");

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        provideReloadExtension();
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "   if (message.successful) {" +
                "       readyLatch.countDown();" +
                "   }" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Calling reload() results in the state being saved.
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();

        provideReloadExtension();
        evaluateScript("cometd.configure({url: '" + url + "', logLevel: '" + getLogLevel() + "'});");
        // Leave the default transports so that we can test if the previous transport is the one used on reload
        evaluateScript("cometd.registerTransport('" + transportName + "', originalTransports['" + transportName + "']);");

        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "   if (message.successful) {" +
                "       readyLatch.countDown();" +
                "   }" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assert.assertEquals(clientId, newClientId);

        String transportType = evaluateScript("cometd.getTransport().getType();");
        Assert.assertEquals(transportName, transportType);

        evaluateScript("cometd.disconnect();");
        Thread.sleep(1000);

        // Be sure the sessionStorage item has been removed on disconnect.
        Boolean reloadState = evaluateScript("window.sessionStorage.getItem('org.cometd.reload') == null;");
        Assert.assertTrue(reloadState);
    }

    @Test
    public void testReloadAcrossServerRestart() throws Exception {
        provideReloadExtension();
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");
        evaluateScript("var stopLatch = new Latch(1);");
        Latch stopLatch = javaScript.get("stopLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "    if (message.successful) {" +
                "        readyLatch.countDown();" +
                "    } else {" +
                "        stopLatch.countDown();" +
                "    }" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        // Stop the server
        int port = connector.getLocalPort();
        server.stop();
        Assert.assertTrue(stopLatch.await(5000));

        // Disconnect
        evaluateScript("cometd.disconnect();");

        // Restart the server
        connector.setPort(port);
        server.start();

        // Reload the page
        evaluateScript("cometd.reload();");
        destroyPage();
        initPage();

        provideReloadExtension();
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");

        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");
        evaluateScript("" +
                "var failures = 0;" +
                "cometd.addListener('/meta/connect', function(message) {" +
                "    if (message.successful) {" +
                "        readyLatch.countDown();" +
                "    } else {" +
                "        ++failures;" +
                "    }" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));
        // Must not have failed with a 402::Unknown Client error
        Assert.assertEquals(0, ((Number)javaScript.get("failures")).intValue());

        disconnect();
    }

    @Test
    public void testReloadWithSubscriptionAndPublish() throws Exception {
        evaluateApplication();
        Latch latch = javaScript.get("latch");
        Assert.assertTrue(latch.await(5000));

        // Calling reload() results in the state being saved.
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();

        evaluateApplication();
        latch = javaScript.get("latch");
        Assert.assertTrue(latch.await(5000));

        // Check that handshake was faked
        evaluateScript("window.assert(extHandshake === null, 'extHandshake');");
        evaluateScript("window.assert(rcvHandshake !== null, 'rcvHandshake');");
        // Check that subscription went out
        evaluateScript("window.assert(extSubscribe !== null, 'extSubscribe');");
        evaluateScript("window.assert(rcvSubscribe === null, 'rcvSubscribe');");
        // Check that publish went out
        evaluateScript("window.assert(extPublish !== null, 'extPublish');");

        disconnect();
    }

    private void evaluateApplication() throws Exception {
        evaluateScript("" +
                "var latch = new Latch(1);" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "" +
                "var extHandshake = null;" +
                "var extSubscribe = null;" +
                "var extPublish = null;" +
                "cometd.registerExtension('test', {" +
                "   outgoing: function(message) {" +
                "       if (message.channel === '/meta/handshake') {" +
                "           extHandshake = message;" +
                "       } else if (message.channel === '/meta/subscribe') {" +
                "           extSubscribe = message;" +
                "       } else if (!/^\\/meta\\//.test(message.channel)) {" +
                "           extPublish = message;" +
                "       }" +
                "   }" +
                "});");
        provideReloadExtension();
        evaluateScript("" +
                "/* Override receive() since it's the method called by the extension to fake responses */" +
                "var rcvHandshake = null;" +
                "var rcvSubscribe = null;" +
                "var _receive = cometd.receive;" +
                "cometd.receive = function(message) {" +
                "   cometd._debug('Received message', JSON.stringify(message));" +
                "   _receive(message);" +
                "   if (message.channel === '/meta/handshake') {" +
                "       rcvHandshake = message;" +
                "   } else if (message.channel === '/meta/subscribe') {" +
                "       rcvSubscribe = message;" +
                "   }" +
                "};" +
                "" +
                "var _connected = false;" +
                "function _init(message) {" +
                "   var wasConnected = _connected;" +
                "   _connected = message.successful;" +
                "   if (!wasConnected && _connected) {" +
                "       cometd.batch(function() {" +
                "           cometd.subscribe('/foo', function() { latch.countDown(); });" +
                "           cometd.publish('/foo', {});" +
                "       });" +
                "   }" +
                "}" +
                "" +
                "cometd.addListener('/meta/connect', _init);" +
                "cometd.handshake();");
    }

    @Test
    public void testReloadWithHandshakeCallback() throws Exception {
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = javaScript.get("readyLatch");

        provideReloadExtension();
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("cometd.handshake(function(message) {" +
                "    if (message.successful) {" +
                "        readyLatch.countDown();" +
                "    }" +
                "});");
        Assert.assertTrue(readyLatch.await(5000));

        // Wait that the long poll is established before reloading
        Thread.sleep(metaConnectPeriod / 2);

        // Reload the page
        evaluateScript("cometd.reload();");
        destroyPage();
        initPage();

        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = javaScript.get("readyLatch");

        provideReloadExtension();
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("cometd.handshake(function(message) {" +
                "    if (message.successful) {" +
                "        readyLatch.countDown();" +
                "    }" +
                "});");
        Assert.assertTrue(readyLatch.await(5000));

        disconnect();
    }
}
