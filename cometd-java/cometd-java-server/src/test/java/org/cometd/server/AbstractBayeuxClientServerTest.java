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

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;

public abstract class AbstractBayeuxClientServerTest extends AbstractBayeuxServerTest
{
    protected HttpClient httpClient;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        httpClient = new HttpClient();
        httpClient.start();
    }

    @Override
    protected void tearDown() throws Exception
    {
        httpClient.stop();
        super.tearDown();
    }

    protected String extractClientId(ContentExchange handshake) throws UnsupportedEncodingException
    {
        String content = handshake.getResponseContent();
        Matcher matcher = Pattern.compile("\"clientId\"\\s*:\\s*\"([^\"]*)\"").matcher(content);
        assertTrue(matcher.find());
        String clientId = matcher.group(1);
        assertTrue(clientId.length() > 0);
        return clientId;
    }

    protected String extractBayeuxCookie(ContentExchange handshake)
    {
        HttpFields headers = handshake.getResponseFields();
        Buffer cookie = headers.get(HttpHeaders.SET_COOKIE_BUFFER);
        String cookieName = "BAYEUX_BROWSER";
        Matcher matcher = Pattern.compile(cookieName + "=([^;]*)").matcher(cookie.toString());
        assertTrue(matcher.find());
        String bayeuxCookie = matcher.group(1);
        assertTrue(bayeuxCookie.length() > 0);
        return cookieName + "=" + bayeuxCookie;
    }

    protected ContentExchange newBayeuxExchange(String requestBody) throws UnsupportedEncodingException
    {
        ContentExchange result = new ContentExchange(true);
        result.setURL(cometdURL);
        result.setMethod(HttpMethods.POST);
        result.setRequestContentType("application/json;charset=UTF-8");
        result.setRequestContent(new ByteArrayBuffer(requestBody, "UTF-8"));
        return result;
    }
}
