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
package org.cometd.server;

import java.io.UnsupportedEncodingException;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

public abstract class AbstractBayeuxClientServerTest extends AbstractBayeuxServerTest {
    protected HttpClient httpClient;

    protected AbstractBayeuxClientServerTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void startHttpClient() throws Exception {
        httpClient = new HttpClient();
        httpClient.start();
    }

    @After
    public void stopHttpClient() throws Exception {
        httpClient.stop();
    }

    protected String extractClientId(ContentResponse handshake) {
        String content = handshake.getContentAsString();
        Matcher matcher = Pattern.compile("\"clientId\"\\s*:\\s*\"([^\"]*)\"").matcher(content);
        Assert.assertTrue(matcher.find());
        String clientId = matcher.group(1);
        Assert.assertTrue(clientId.length() > 0);
        return clientId;
    }

    protected String extractCookie(String name) {
        List<HttpCookie> cookies = httpClient.getCookieStore().get(URI.create(cometdURL));
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
