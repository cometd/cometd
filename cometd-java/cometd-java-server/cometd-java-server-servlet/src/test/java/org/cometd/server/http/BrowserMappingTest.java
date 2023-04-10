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
package org.cometd.server.http;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cometd.server.AbstractBayeuxClientServerTest;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.servlet.transport.JSONTransport;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class BrowserMappingTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testBayeuxBrowserMapping(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        AbstractHttpTransport transport = new JSONTransport(bayeux);
        transport.init();

        ServerSessionImpl session = new ServerSessionImpl(bayeux);
        session.setBrowserId("browser1");
        Assertions.assertTrue(transport.incBrowserId(session, false));
        Assertions.assertFalse(transport.incBrowserId(session, false));
        transport.decBrowserId(session, false);
        Assertions.assertTrue(transport.incBrowserId(session, false));
        transport.decBrowserId(session, false);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSameDomainWithCookieHoldsConnect(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));

        long begin = System.nanoTime();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        Assertions.assertTrue(elapsed >= (timeout - timeout / 10), "" + elapsed);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSameDomainWithoutCookieYieldsUnknownClient(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));

        // Remove cookie.
        httpClient.getHttpCookieStore().clear();

        long begin = System.nanoTime();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertFalse(isSuccessful(response));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        Assertions.assertTrue(elapsed < timeout / 2, "" + elapsed);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSameDomainWithoutCookieTrustClientSessionHoldsConnect(String serverTransport) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractHttpTransport.TRUST_CLIENT_SESSION_OPTION, String.valueOf(true));
        startServer(serverTransport, options);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));

        String clientId = extractClientId(response);

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));

        // Remove cookie.
        httpClient.getHttpCookieStore().clear();

        long begin = System.nanoTime();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        Assertions.assertTrue(elapsed >= timeout - (timeout / 10), "" + elapsed);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDifferentDomainWithoutCookieTrustClientSessionHoldsConnect(String serverTransport) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractHttpTransport.TRUST_CLIENT_SESSION_OPTION, String.valueOf(true));
        startServer(serverTransport, options);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));

        String clientId = extractClientId(response);

        // Remove cookie.
        httpClient.getHttpCookieStore().clear();

        // First connect always returns immediately
        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect1.headers(headers -> headers.put(HttpHeader.HOST, "127.0.0.1:" + port));
        connect1.headers(headers -> headers.put("Origin", "http://localhost:" + port));
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));

        long begin = System.nanoTime();
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect2.headers(headers -> headers.put(HttpHeader.HOST, "127.0.0.1:" + port));
        connect2.headers(headers -> headers.put("Origin", "http://localhost:" + port));
        response = connect2.timeout(timeout * 2, TimeUnit.SECONDS).send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        Assertions.assertTrue(elapsed >= (timeout - timeout / 10), "" + elapsed);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCookieConfiguration(String serverTransport) throws Exception {
        String cookieName = "cookie_name";
        String cookieDomain = "cookie_domain";
        String cookiePath = "cookie_path";
        String cookieSameSite = "Lax";

        Map<String, String> options = new HashMap<>();
        options.put(JSONTransport.BROWSER_COOKIE_NAME_OPTION, cookieName);
        options.put(JSONTransport.BROWSER_COOKIE_DOMAIN_OPTION, cookieDomain);
        options.put(JSONTransport.BROWSER_COOKIE_PATH_OPTION, cookiePath);
        options.put(JSONTransport.BROWSER_COOKIE_SAME_SITE_OPTION, cookieSameSite);
        startServer(serverTransport, options);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertTrue(isSuccessful(response));

        HttpFields headers = response.getHeaders();
        String cookie = headers.get(HttpHeader.SET_COOKIE);
        List<String> parts = Arrays.stream(cookie.split(";")).map(String::trim).toList();
        boolean hasCookieName = false;
        for (String part : parts) {
            if (part.startsWith(cookieName + "=")) {
                hasCookieName = true;
                break;
            }
        }
        Assertions.assertTrue(hasCookieName);
        Assertions.assertTrue(parts.contains("Path=" + cookiePath));
        Assertions.assertTrue(parts.contains("Domain=" + cookieDomain));
        Assertions.assertTrue(parts.contains("HttpOnly"));
        Assertions.assertTrue(parts.contains("SameSite=" + cookieSameSite));
    }

    private boolean isSuccessful(ContentResponse response) {
        String content = response.getContentAsString();
        Matcher matcher = Pattern.compile("\"successful\"\\s*:\\s*(true|false)").matcher(content);
        Assertions.assertTrue(matcher.find());
        return Boolean.parseBoolean(matcher.group(1));
    }
}
