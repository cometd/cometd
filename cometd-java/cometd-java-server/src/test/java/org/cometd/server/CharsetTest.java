/*
 * Copyright (c) 2008-2014 the original author or authors.
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

import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CharsetTest extends AbstractBayeuxClientServerTest
{
    @Before
    public void prepare() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testMissingContentTypeWithLongPolling() throws Exception
    {
        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        Assert.assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);
        String bayeuxCookie = extractBayeuxCookie(handshake);

        final String data = new String(new byte[]{(byte)0xC3, (byte)0xA9}, "UTF-8");
        String channelName = "/test_charset";
        bayeux.createChannelIfAbsent(channelName).getReference().addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                String messageData = (String)message.getData();
                Assert.assertEquals(data, messageData);
                return true;
            }
        });

        ContentExchange publish = newBayeuxExchange("[{" +
                "\"channel\":\"" + channelName + "\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"data\":\"" + data + "\"" +
                "}]");
        // In some cross domain configuration (for example IE9 using XDomainRequest),
        // the Content-Type header is not sent, and we must behave well even if it's missing
        publish.getRequestFields().remove(HttpHeaders.CONTENT_TYPE_BUFFER);
        publish.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(publish);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        Assert.assertEquals(200, publish.getResponseStatus());

        ContentExchange disconnect = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        httpClient.send(disconnect);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, disconnect.waitForDone());
        Assert.assertEquals(200, disconnect.getResponseStatus());
    }

    @Test
    public void testContentTypeWithISO_8859_7() throws Exception
    {
        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        Assert.assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);
        String bayeuxCookie = extractBayeuxCookie(handshake);

        // Greek encoding
        String encoding = "ISO-8859-7";
        // Lowercase greek letter alpha
        final String data = new String(new byte[]{(byte)0xE1}, encoding);
        String channelName = "/test_charset";
        bayeux.createChannelIfAbsent(channelName).getReference().addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                String messageData = (String)message.getData();
                Assert.assertEquals(data, messageData);
                return true;
            }
        });

        ContentExchange publish = new ContentExchange(true);
        configureBayeuxExchange(publish, "[{" +
                "\"channel\":\"" + channelName + "\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"data\":\"" + data + "\"" +
                "}]", encoding);
        publish.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(publish);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        Assert.assertEquals(200, publish.getResponseStatus());

        ContentExchange disconnect = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        httpClient.send(disconnect);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, disconnect.waitForDone());
        Assert.assertEquals(200, disconnect.getResponseStatus());
    }
}
