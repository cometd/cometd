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
package org.cometd.server.handler;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractBayeuxClientServerTest extends AbstractBayeuxServerTest
{
    protected HttpClient httpClient;

    @BeforeEach
    public void startHttpClient() throws Exception {
        httpClient = new HttpClient();
        httpClient.start();
    }

    @AfterEach
    public void stopHttpClient() throws Exception {
        httpClient.stop();
    }

    protected String extractClientId(ContentResponse handshake) {
        String content = handshake.getContentAsString();
        Matcher matcher = Pattern.compile("\"clientId\"\\s*:\\s*\"([^\"]*)\"").matcher(content);
        Assertions.assertTrue(matcher.find());
        String clientId = matcher.group(1);
        Assertions.assertTrue(clientId.length() > 0);
        return clientId;
    }

    protected String extractCookie(String name) {
        List<HttpCookie> cookies = httpClient.getHttpCookieStore().match(URI.create(cometdURL));
        for (HttpCookie cookie : cookies) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }

    protected Request newBayeuxRequest(String requestBody) throws UnsupportedEncodingException {
        Request request = httpClient.newRequest(cometdURL);
        configureBayeuxRequest(request, requestBody, "UTF-8");
        return request;
    }

    protected void configureBayeuxRequest(Request request, String requestBody, String encoding) {
        request.timeout(5, TimeUnit.SECONDS);
        request.method(HttpMethod.POST);
        request.body(new StringRequestContent("application/json;charset=" + encoding, requestBody, Charset.forName(encoding)));
    }
}
