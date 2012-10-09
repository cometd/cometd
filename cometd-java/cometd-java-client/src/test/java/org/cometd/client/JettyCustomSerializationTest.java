/*
 * Copyright (c) 2012 the original author or authors.
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

package org.cometd.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.JettyJSONContextServer;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.Assert;
import org.junit.Test;

public class JettyCustomSerializationTest extends ClientServerTest
{
    @Test
    public void testJettyCustomSerialization() throws Exception
    {
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put(BayeuxServerImpl.JSON_CONTEXT, TestJettyJSONContextServer.class.getName());
        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put(ClientTransport.JSON_CONTEXT, new TestJettyJSONContextClient());

        startServer(serverOptions);

        String channelName = "/data";
        final String content = "random";
        final CountDownLatch latch = new CountDownLatch(1);

        LocalSession service = bayeux.newLocalSession("custom_serialization");
        service.handshake();
        service.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                Data data = (Data)message.getData();
                Assert.assertEquals(content, data.content);
                Map<String, Object> ext = message.getExt();
                Assert.assertNotNull(ext);
                Extra extra = (Extra)ext.get("extra");
                Assert.assertEquals(content, extra.content);
                latch.countDown();
            }
        });

        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(clientOptions, httpClient));
        client.setDebugEnabled(debugTests());
        client.addExtension(new ExtraExtension(content));

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        // Wait for the connect to establish
        Thread.sleep(1000);

        client.getChannel(channelName).publish(new Data(content));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    private static class ExtraExtension extends ClientSession.Extension.Adapter
    {
        private final String content;

        public ExtraExtension(String content)
        {
            this.content = content;
        }

        @Override
        public boolean send(ClientSession session, Message.Mutable message)
        {
            Map<String, Object> ext = message.getExt(true);
            ext.put("extra", new Extra(content));
            return true;
        }
    }

    private static class TestJettyJSONContextClient extends JettyJSONContextClient
    {
        private TestJettyJSONContextClient()
        {
            getJSON().addConvertor(Data.class, new DataConvertor());
            getJSON().addConvertor(Extra.class, new ExtraConvertor());
        }
    }

    public static class TestJettyJSONContextServer extends JettyJSONContextServer
    {
        public TestJettyJSONContextServer()
        {
            getJSON().addConvertor(Data.class, new DataConvertor());
            getJSON().addConvertor(Extra.class, new ExtraConvertor());
        }
    }

    private static class Data
    {
        private String content;

        private Data(String content)
        {
            this.content = content;
        }
    }

    private static class Extra
    {
        private String content;

        private Extra(String content)
        {
            this.content = content;
        }
    }

    private static class DataConvertor implements JSON.Convertor
    {
        public void toJSON(Object object, JSON.Output output)
        {
            Data data = (Data)object;
            output.addClass(Data.class);
            output.add("content", data.content);
        }

        public Object fromJSON(Map map)
        {
            String content = (String)map.get("content");
            return new Data(content);
        }
    }

    private static class ExtraConvertor implements JSON.Convertor
    {
        public void toJSON(Object object, JSON.Output output)
        {
            Extra extra = (Extra)object;
            output.addClass(Extra.class);
            output.add("content", extra.content);
        }

        public Object fromJSON(Map map)
        {
            String content = (String)map.get("content");
            return new Extra(content);
        }
    }
}
