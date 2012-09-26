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
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.Assert;
import org.junit.Test;

public class BrowserMappingTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testBayeuxBrowserMapping() throws Exception
    {
        LongPollingTransport transport = new JSONTransport(bayeux);

        String browserId = "browser1";
        Assert.assertTrue(transport.incBrowserId(browserId));
        Assert.assertFalse(transport.incBrowserId(browserId));
        transport.decBrowserId(browserId);
        Assert.assertTrue(transport.incBrowserId(browserId));
        transport.decBrowserId(browserId);
    }

    @Test
    public void testSameDomainWithCookieHoldsConnect() throws Exception
    {
        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        long begin = System.currentTimeMillis();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.send().get(timeout * 2, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());
        long elapsed = System.currentTimeMillis() - begin;
        Assert.assertTrue("" + elapsed, elapsed >= timeout);
    }

    @Test
    public void testSameDomainWithoutCookieDoesNotHoldConnect() throws Exception
    {
        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        // Remove cookie
        httpClient.getCookieStore().clear();

        long begin = System.nanoTime();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.send().get(timeout * 2, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        Assert.assertTrue("" + elapsed, elapsed < timeout / 2);
    }

    @Test
    public void testSameDomainWithoutCookieWithOptionHoldsConnect() throws Exception
    {
        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        AbstractServerTransport transport = (AbstractServerTransport)bayeux.getTransport("long-polling");
        transport.setOption(LongPollingTransport.ALLOW_MULTI_SESSIONS_NO_BROWSER_OPTION, true);
        Method init = transport.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(transport);

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        long begin = System.currentTimeMillis();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.send().get(timeout * 2, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());
        long elapsed = System.currentTimeMillis() - begin;
        Assert.assertTrue("" + elapsed, elapsed >= timeout);
    }

    @Test
    public void testDifferentDomainWithoutCookieHoldsConnect() throws Exception
    {
        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect1.header(HttpHeader.HOST.asString(), "http://127.0.0.1:" + port);
        connect1.header("Origin", "http://localhost:" + port);
        response = connect1.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        long begin = System.currentTimeMillis();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect2.header(HttpHeader.HOST.asString(), "http://127.0.0.1:" + port);
        connect2.header("Origin", "http://localhost:" + port);
        response = connect2.send().get(timeout * 2, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());
        long elapsed = System.currentTimeMillis() - begin;
        Assert.assertTrue("" + elapsed, elapsed >= timeout);
    }
}
