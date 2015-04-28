/*
 * Copyright (c) 2008-2015 the original author or authors.
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

import org.cometd.server.AbstractBayeuxClientServerTest;
import org.cometd.server.AbstractServerTransport;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class BrowserMappingTest extends AbstractBayeuxClientServerTest
{
    public BrowserMappingTest(String serverTransport)
    {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testBayeuxBrowserMapping() throws Exception
    {
        AbstractHttpTransport transport = new JSONTransport(bayeux);
        transport.init();

        String browserId = "browser1";
        Assert.assertTrue(transport.incBrowserId(browserId, null));
        Assert.assertFalse(transport.incBrowserId(browserId, null));
        transport.decBrowserId(browserId, null);
        Assert.assertTrue(transport.incBrowserId(browserId, null));
        transport.decBrowserId(browserId, null);
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
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());

        long begin = System.currentTimeMillis();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assert.assertEquals(200, response.getStatus());
        long elapsed = System.currentTimeMillis() - begin;
        Assert.assertTrue("" + elapsed, elapsed >= (timeout - timeout / 10));
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
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());

        // Remove cookie
        httpClient.getCookieStore().removeAll();

        long begin = System.nanoTime();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assert.assertEquals(200, response.getStatus());
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
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        AbstractServerTransport transport = (AbstractServerTransport)bayeux.getTransport("long-polling");
        transport.setOption(AbstractHttpTransport.ALLOW_MULTI_SESSIONS_NO_BROWSER_OPTION, true);
        transport.init();

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());

        long begin = System.currentTimeMillis();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assert.assertEquals(200, response.getStatus());
        long elapsed = System.currentTimeMillis() - begin;
        Assert.assertTrue("" + elapsed, elapsed >= (timeout - timeout / 10));
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
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect1.header(HttpHeader.HOST.asString(), "http://127.0.0.1:" + port);
        connect1.header("Origin", "http://localhost:" + port);
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());

        long begin = System.currentTimeMillis();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect2.header(HttpHeader.HOST.asString(), "http://127.0.0.1:" + port);
        connect2.header("Origin", "http://localhost:" + port);
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assert.assertEquals(200, response.getStatus());
        long elapsed = System.currentTimeMillis() - begin;
        Assert.assertTrue("" + elapsed, elapsed >= (timeout - timeout / 10));
    }

    @Test
    public void testCookieConfiguration() throws Exception
    {
        String cookieName = "cookie_name";
        String cookieDomain = "cookie_domain";
        String cookiePath = "cookie_path";

        JSONTransport transport = new JSONTransport(bayeux);
        transport.setOption(JSONTransport.BROWSER_COOKIE_NAME_OPTION, cookieName);
        transport.setOption(JSONTransport.BROWSER_COOKIE_DOMAIN_OPTION, cookieDomain);
        transport.setOption(JSONTransport.BROWSER_COOKIE_PATH_OPTION, cookiePath);
        transport.init();
        bayeux.setTransports(transport);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        HttpFields headers = response.getHeaders();
        String cookie = headers.get(HttpHeader.SET_COOKIE);
        String[] parts = cookie.split(";");
        boolean hasCookieName = false;
        for (String part : parts)
        {
            if (part.startsWith(cookieName + "="))
                hasCookieName = true;
        }
        Assert.assertTrue(hasCookieName);
        Assert.assertTrue(Arrays.asList(parts).contains("Path=" + cookiePath));
        Assert.assertTrue(Arrays.asList(parts).contains("Domain=" + cookieDomain));
    }
}
