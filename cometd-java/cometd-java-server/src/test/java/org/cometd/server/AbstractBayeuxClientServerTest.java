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

package org.cometd.server;

import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

public abstract class AbstractBayeuxClientServerTest extends AbstractBayeuxServerTest
{
    protected HttpClient httpClient;

    @Before
    public void startHttpClient() throws Exception
    {
        httpClient = new HttpClient();
        httpClient.start();
    }

    @After
    public void stopHttpClient() throws Exception
    {
        httpClient.stop();
    }

    protected String extractClientId(ContentResponse handshake) throws UnsupportedEncodingException
    {
        String content = new String(handshake.content(), "UTF-8");
        Matcher matcher = Pattern.compile("\"clientId\"\\s*:\\s*\"([^\"]*)\"").matcher(content);
        Assert.assertTrue(matcher.find());
        String clientId = matcher.group(1);
        Assert.assertTrue(clientId.length() > 0);
        return clientId;
    }

    protected Request newBayeuxRequest(String requestBody) throws UnsupportedEncodingException
    {
        Request request = httpClient.newRequest(cometdURL);
        configureBayeuxRequest(request, requestBody, "UTF-8");
        return request;
    }

    protected void configureBayeuxRequest(Request request, String requestBody, String encoding) throws UnsupportedEncodingException
    {
        request.method(HttpMethod.POST);
        request.header(HttpHeader.CONTENT_TYPE.asString(), "application/json;charset=" + encoding);
        request.content(new StringContentProvider(requestBody, encoding));
    }
}
