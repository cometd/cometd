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

package org.cometd.server.transport;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import org.cometd.server.AbstractBayeuxClientServerTest;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;

public class BrowserMappingTest extends AbstractBayeuxClientServerTest
{
    private BayeuxServerImpl bayeux;

    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        this.bayeux = bayeux;
    }

    public void testBayeuxBrowserMapping() throws Exception
    {
        LongPollingTransport transport = new JSONTransport(bayeux);

        String browserId = "browser1";
        assertTrue(transport.incBrowserId(browserId));
        assertFalse(transport.incBrowserId(browserId));
        transport.decBrowserId(browserId);
        assertTrue(transport.incBrowserId(browserId));
        transport.decBrowserId(browserId);
    }

    public void testSameDomainWithCookieHoldsConnect() throws Exception
    {
        ContentExchange handshake = newBayeuxExchange("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);
        String bayeuxCookie = extractBayeuxCookie(handshake);

        // First connect always returns immediately
        ContentExchange connect1 = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect1.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect1);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect1.waitForDone());
        assertEquals(200, connect1.getResponseStatus());

        long begin = System.nanoTime();
        ContentExchange connect2 = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect2.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect2);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect2.waitForDone());
        assertEquals(200, connect2.getResponseStatus());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertTrue("" + elapsed, elapsed >= timeout);
    }

    public void testSameDomainWithoutCookieDoesNotHoldConnect() throws Exception
    {
        ContentExchange handshake = newBayeuxExchange("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);

        // First connect always returns immediately
        ContentExchange connect1 = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        httpClient.send(connect1);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect1.waitForDone());
        assertEquals(200, connect1.getResponseStatus());

        long begin = System.nanoTime();
        ContentExchange connect2 = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        httpClient.send(connect2);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect2.waitForDone());
        assertEquals(200, connect2.getResponseStatus());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertTrue("" + elapsed, elapsed < timeout / 2);
    }

    public void testSameDomainWithoutCookieWithOptionHoldsConnect() throws Exception
    {
        ContentExchange handshake = newBayeuxExchange("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        AbstractServerTransport transport = (AbstractServerTransport)bayeux.getTransport("long-polling");
        transport.setOption(LongPollingTransport.ALLOW_MULTI_SESSIONS_NO_BROWSER_OPTION, true);
        Method init = transport.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(transport);

        String clientId = extractClientId(handshake);

        // First connect always returns immediately
        ContentExchange connect1 = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        httpClient.send(connect1);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect1.waitForDone());
        assertEquals(200, connect1.getResponseStatus());

        long begin = System.nanoTime();
        ContentExchange connect2 = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        httpClient.send(connect2);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect2.waitForDone());
        assertEquals(200, connect2.getResponseStatus());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertTrue("" + elapsed, elapsed >= timeout);
    }

    public void testDifferentDomainWithoutCookieHoldsConnect() throws Exception
    {
        ContentExchange handshake = newBayeuxExchange("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);

        // First connect always returns immediately
        ContentExchange connect1 = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect1.setRequestHeader(HttpHeaders.HOST, "http://127.0.0.1:" + port);
        connect1.setRequestHeader("Origin", "http://localhost:" + port);
        httpClient.send(connect1);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect1.waitForDone());
        assertEquals(200, connect1.getResponseStatus());

        long begin = System.nanoTime();
        ContentExchange connect2 = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect2.setRequestHeader(HttpHeaders.HOST, "http://127.0.0.1:" + port);
        connect2.setRequestHeader("Origin", "http://localhost:" + port);
        httpClient.send(connect2);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect2.waitForDone());
        assertEquals(200, connect2.getResponseStatus());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertTrue("" + elapsed, elapsed >= timeout);
    }
}
