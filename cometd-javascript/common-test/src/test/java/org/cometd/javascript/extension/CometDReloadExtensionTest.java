/*
 * Copyright (c) 2010 the original author or authors.
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

import org.cometd.javascript.AbstractCometDTest;
import org.cometd.javascript.Latch;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CometDReloadExtensionTest extends AbstractCometDTest
{
    @Before
    public void initExtension() throws Exception
    {
        provideReloadExtension();
    }

    @Test
    public void testReloadWithConfiguration() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        String cookieName = "reload.test";
        evaluateScript("" +
                "cometd.unregisterExtension('reload');" +
                "cometd.registerExtension('reload', new org.cometd.ReloadExtension({" +
                "    cookieName: '" + cookieName + "'," +
                "    cookiePath: '/test'," +
                "    cookieMaxAge: 4" +
                "}));" +
                "" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        // Wait that the long poll is established before reloading
        Thread.sleep(longPollingPeriod / 2);

        evaluateScript("cometd.reload();");
        String cookies = evaluateScript("window.document.cookie");
        Assert.assertTrue(cookies.startsWith(cookieName + "="));

        evaluateScript("cometd.disconnect(true)");
    }

    @Test
    public void testReloadedHandshakeContainsExtension() throws Exception
    {
        bayeuxServer.addExtension(new AcknowledgedMessagesExtension());

        defineClass(Latch.class);
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        // Wait that the long poll is established before reloading
        Thread.sleep(longPollingPeriod / 2);

        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();
        initExtension();

        defineClass(Latch.class);
        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "var ext = undefined;" +
                "cometd.addListener('/meta/handshake', function(message)" +
                "{" +
                "   if (message.successful)" +
                "      ext = message.ext;" +
                "});" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        evaluateScript("" +
                "window.assert(ext !== undefined, 'ext must be present');" +
                "window.assert(ext.reload === true, 'ext.reload must be true');" +
                "window.assert(ext.ack === true, 'ext.ack must be true');");

        evaluateScript("cometd.disconnect(true)");
    }

    @Test
    public void testReloadDoesNotExpire() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Wait that the long poll is established before reloading
        Thread.sleep(longPollingPeriod / 2);

        // Calling reload() results in the cookie being written
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();
        initExtension();

        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        evaluateScript("var expireLatch = new Latch(1);");
        Latch expireLatch = get("expireLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{" +
                "   if (message.successful) " +
                "       readyLatch.countDown();" +
                "   else" +
                "       expireLatch.countDown();" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assert.assertEquals(clientId, newClientId);

        // Make sure that reloading will not expire the client on the server
        Assert.assertFalse(expireLatch.await(expirationPeriod + longPollingPeriod));

        evaluateScript("cometd.disconnect(true);");
    }

    @Test
    public void testReloadWithWebSocketTransport() throws Exception
    {
        testReloadWithTransport(cometdURL, "websocket");
    }

    @Test
    public void testReloadWithLongPollingTransport() throws Exception
    {
        testReloadWithTransport(cometdURL, "long-polling");
    }

    @Test
    public void testReloadWithCallbackPollingTransport() throws Exception
    {
        // Make the CometD URL different to simulate the cross domain request
        String url = cometdURL.replace("localhost", "127.0.0.1");
        testReloadWithTransport(url, "callback-polling");
    }

    private void testReloadWithTransport(String url, String transportName) throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("cometd.configure({url: '" + url + "', logLevel: '" + getLogLevel() + "'});");
        evaluateScript("cometd.unregisterTransports();");
        evaluateScript("cometd.registerTransport('" + transportName + "', originalTransports['" + transportName + "']);");

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{ " +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        // Get the clientId
        String clientId = evaluateScript("cometd.getClientId();");

        // Calling reload() results in the cookie being written
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();
        initExtension();

        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + url + "', logLevel: '" + getLogLevel() + "'});");
        // Leave the default transports so that we can test if the previous transport is the one used on reload
        evaluateScript("cometd.registerTransport('" + transportName + "', originalTransports['" + transportName + "']);");

        evaluateScript("var readyLatch = new Latch(1);");
        readyLatch = get("readyLatch");
        evaluateScript("" +
                "cometd.addListener('/meta/connect', function(message) " +
                "{" +
                "   if (message.successful) readyLatch.countDown(); " +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(5000));

        String newClientId = evaluateScript("cometd.getClientId();");
        Assert.assertEquals(clientId, newClientId);

        String transportType = (String)evaluateScript("cometd.getTransport().getType();");
        Assert.assertEquals(transportName, transportType);

        evaluateScript("cometd.disconnect();");
        Thread.sleep(1000);

        // Be sure the cookie has been removed on disconnect
        Boolean noCookie = evaluateScript("org.cometd.COOKIE.get('org.cometd.reload') == null;");
        Assert.assertTrue(noCookie);
    }

    @Test
    public void testReloadWithSubscriptionAndPublish() throws Exception
    {
        defineClass(Latch.class);
        evaluateApplication();
        Latch latch = (Latch)get("latch");
        Assert.assertTrue(latch.await(5000));

        // Calling reload() results in the cookie being written
        evaluateScript("cometd.reload();");

        // Reload the page
        destroyPage();
        initPage();
        initExtension();

        defineClass(Latch.class);
        evaluateApplication();
        latch = (Latch)get("latch");
        Assert.assertTrue(latch.await(5000));

        // Check that handshake was faked
        evaluateScript("window.assert(extHandshake === null, 'extHandshake');");
        evaluateScript("window.assert(rcvHandshake !== null, 'rcvHandshake');");
        // Check that subscription went out
        evaluateScript("window.assert(extSubscribe !== null, 'extSubscribe');");
        evaluateScript("window.assert(rcvSubscribe === null, 'rcvSubscribe');");
        // Check that publish went out
        evaluateScript("window.assert(extPublish !== null, 'extPublish');");

        evaluateScript("cometd.disconnect(true);");
    }

    private void evaluateApplication() throws Exception
    {
        evaluateScript("" +
                "var latch = new Latch(1);" +
                "cometd.configure({url: '" + cometdURL + "', logLevel: '" + getLogLevel() + "'});" +
                "" +
                "var extHandshake = null;" +
                "var extSubscribe = null;" +
                "var extPublish = null;" +
                "cometd.registerExtension('test', {" +
                "   outgoing: function(message) " +
                "   {" +
                "       if (message.channel === '/meta/handshake')" +
                "           extHandshake = message;" +
                "       else if (message.channel === '/meta/subscribe')" +
                "           extSubscribe = message;" +
                "       else if (!/^\\/meta\\//.test(message.channel))" +
                "           extPublish = message;" +
                "   }" +
                "});" +
                "" +
                "/* Override receive() since it's the method called by the extension to fake responses */" +
                "var rcvHandshake = null;" +
                "var rcvSubscribe = null;" +
                "var _receive = cometd.receive;" +
                "cometd.receive = function(message)" +
                "{" +
                "   cometd._debug('Received message', org.cometd.JSON.toJSON(message));" +
                "   _receive(message);" +
                "   if (message.channel === '/meta/handshake')" +
                "       rcvHandshake = message;" +
                "   else if (message.channel === '/meta/subscribe')" +
                "       rcvSubscribe = message;" +
                "};" +
                "" +
                "var _connected = false;" +
                "function _init(message)" +
                "{" +
                "   var wasConnected = _connected;" +
                "   _connected = message.successful;" +
                "   if (!wasConnected && _connected)" +
                "   {" +
                "       cometd.batch(function()" +
                "       {" +
                "           cometd.subscribe('/foo', function(message) { latch.countDown(); });" +
                "           cometd.publish('/foo', {});" +
                "       });" +
                "   }" +
                "}" +
                "" +
                "cometd.addListener('/meta/connect', _init);" +
                "cometd.handshake();");
    }
}
