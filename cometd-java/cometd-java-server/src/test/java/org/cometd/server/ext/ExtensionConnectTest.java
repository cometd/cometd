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

package org.cometd.server.ext;

import java.util.ArrayList;
import java.util.List;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.junit.Assert;
import org.junit.Test;

public class ExtensionConnectTest extends AbstractBayeuxClientServerTest
{
    private CountingExtension extension;

    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        extension = new CountingExtension();
        bayeux.addExtension(extension);
    }

    @Test
    public void testExtension() throws Exception
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

        ContentExchange connect = newBayeuxExchange("[{" +
                                                 "\"channel\": \"/meta/connect\"," +
                                                 "\"clientId\": \"" + clientId + "\"," +
                                                 "\"connectionType\": \"long-polling\"" +
                                                 "}]");
        connect.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, connect.waitForDone());
        Assert.assertEquals(200, connect.getResponseStatus());

        Assert.assertEquals(0, extension.rcvs.size());
        Assert.assertEquals(1, extension.rcvMetas.size());
        Assert.assertEquals(0, extension.sends.size());
        Assert.assertEquals(1, extension.sendMetas.size());
    }

    private class CountingExtension implements BayeuxServer.Extension
    {
        private final List<Message> rcvs = new ArrayList<Message>();
        private final List<Message> rcvMetas = new ArrayList<Message>();
        private final List<Message> sends = new ArrayList<Message>();
        private final List<Message> sendMetas = new ArrayList<Message>();

        public boolean rcv(ServerSession from, ServerMessage.Mutable message)
        {
            rcvs.add(message);
            return true;
        }

        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message)
        {
            if (Channel.META_CONNECT.equals(message.getChannel()))
            {
                rcvMetas.add(message);
            }
            return true;
        }

        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message)
        {
            sends.add(message);
            return true;
        }

        public boolean sendMeta(ServerSession to, ServerMessage.Mutable message)
        {
            if (Channel.META_CONNECT.equals(message.getChannel()))
            {
                sendMetas.add(message);
            }
            return true;
        }
    }
}
