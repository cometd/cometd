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
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class ExtensionUnsubscribeTest extends AbstractBayeuxClientServerTest
{
    private CountingExtension extension = new CountingExtension();

    @Test
    public void testExtension() throws Exception
    {
        bayeux.addExtension(extension);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        String channel = "/foo";
        Request subscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = subscribe.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        Request unsubscribe = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/unsubscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        response = unsubscribe.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        Assert.assertEquals(0, extension.rcvs.size());
        Assert.assertEquals(1, extension.rcvMetas.size());
        Assert.assertEquals(0, extension.sends.size());
        Assert.assertEquals(1, extension.sendMetas.size());
    }

    private class CountingExtension implements BayeuxServer.Extension
    {
        private final List<Message> rcvs = new ArrayList<>();
        private final List<Message> rcvMetas = new ArrayList<>();
        private final List<Message> sends = new ArrayList<>();
        private final List<Message> sendMetas = new ArrayList<>();

        public boolean rcv(ServerSession from, ServerMessage.Mutable message)
        {
            rcvs.add(message);
            return true;
        }

        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message)
        {
            if (Channel.META_UNSUBSCRIBE.equals(message.getChannel()))
                rcvMetas.add(message);
            return true;
        }

        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message)
        {
            sends.add(message);
            return true;
        }

        public boolean sendMeta(ServerSession to, ServerMessage.Mutable message)
        {
            if (Channel.META_UNSUBSCRIBE.equals(message.getChannel()))
                sendMetas.add(message);
            return true;
        }
    }
}
