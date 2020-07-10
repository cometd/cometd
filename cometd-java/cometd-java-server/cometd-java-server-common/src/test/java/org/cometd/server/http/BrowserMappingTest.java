/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.server.http;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.cometd.server.AbstractBayeuxClientServerTest;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.Assert;
import org.junit.Test;

public class BrowserMappingTest extends AbstractBayeuxClientServerTest {
    public BrowserMappingTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testBayeuxBrowserMapping() throws Exception {
        startServer(null);

        AbstractHttpTransport transport = new JSONTransport(bayeux);
        transport.init();

        ServerSessionImpl session = new ServerSessionImpl(bayeux);
        session.setBrowserId("browser1");
        Assert.assertTrue(transport.incBrowserId(session, false));
        Assert.assertFalse(transport.incBrowserId(session, false));
        transport.decBrowserId(session, false);
        Assert.assertTrue(transport.incBrowserId(session, false));
        transport.decBrowserId(session, false);
    }

    @Test
    public void testSameDomainWithCookieHoldsConnect() throws Exception {
        startServer(null);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));

        long begin = System.nanoTime();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        Assert.assertTrue("" + elapsed, elapsed >= (timeout - timeout / 10));
    }

    @Test
    public void testSameDomainWithoutCookieYieldsUnknownClient() throws Exception {
        startServer(null);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));

        // Remove cookie.
        httpClient.getCookieStore().removeAll();

        long begin = System.nanoTime();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertFalse(isSuccessful(response));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        Assert.assertTrue("" + elapsed, elapsed < timeout / 2);
    }

    @Test
    public void testSameDomainWithoutCookieTrustClientSessionHoldsConnect() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractHttpTransport.TRUST_CLIENT_SESSION_OPTION, String.valueOf(true));
        startServer(options);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));

        // Remove cookie.
        httpClient.getCookieStore().removeAll();

        long begin = System.nanoTime();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        Assert.assertTrue("" + elapsed, elapsed >= timeout - (timeout / 10));
    }

    @Test
    public void testDifferentDomainWithoutCookieTrustClientSessionHoldsConnect() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractHttpTransport.TRUST_CLIENT_SESSION_OPTION, String.valueOf(true));
        startServer(options);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));

        String clientId = extractClientId(response);

        // Remove cookie.
        httpClient.getCookieStore().removeAll();

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect1.headers(headers -> headers.put(HttpHeader.HOST, "http://127.0.0.1:" + port));
        connect1.headers(headers -> headers.put("Origin", "http://localhost:" + port));
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));

        long begin = System.nanoTime();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect2.headers(headers -> headers.put(HttpHeader.HOST, "http://127.0.0.1:" + port));
        connect2.headers(headers -> headers.put("Origin", "http://localhost:" + port));
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        Assert.assertTrue("" + elapsed, elapsed >= (timeout - timeout / 10));
    }

    @Test
    public void testCookieConfiguration() throws Exception {
        String cookieName = "cookie_name";
        String cookieDomain = "cookie_domain";
        String cookiePath = "cookie_path";
        String cookieSameSite = "Lax";

        Map<String, String> options = new HashMap<>();
        options.put(JSONTransport.BROWSER_COOKIE_NAME_OPTION, cookieName);
        options.put(JSONTransport.BROWSER_COOKIE_DOMAIN_OPTION, cookieDomain);
        options.put(JSONTransport.BROWSER_COOKIE_PATH_OPTION, cookiePath);
        options.put(JSONTransport.BROWSER_COOKIE_SAME_SITE_OPTION, cookieSameSite);
        startServer(options);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(isSuccessful(response));

        HttpFields headers = response.getHeaders();
        String cookie = headers.get(HttpHeader.SET_COOKIE);
        List<String> parts = Arrays.stream(cookie.split(";")).map(String::trim).collect(Collectors.toList());
        boolean hasCookieName = false;
        for (String part : parts) {
            if (part.startsWith(cookieName + "=")) {
                hasCookieName = true;
            }
        }
        Assert.assertTrue(hasCookieName);
        Assert.assertTrue(parts.contains("Path=" + cookiePath));
        Assert.assertTrue(parts.contains("Domain=" + cookieDomain));
        Assert.assertTrue(parts.contains("HttpOnly"));
        Assert.assertTrue(parts.contains("SameSite=" + cookieSameSite));
    }

    private boolean isSuccessful(ContentResponse response) {
        String content = response.getContentAsString();
        Matcher matcher = Pattern.compile("\"successful\"\\s*:\\s*(true|false)").matcher(content);
        Assert.assertTrue(matcher.find());
        return Boolean.parseBoolean(matcher.group(1));
    }
}
