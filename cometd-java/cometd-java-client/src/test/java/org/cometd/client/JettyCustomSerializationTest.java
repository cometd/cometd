/*
 * Copyright (c) 2008-2019 the original author or authors.
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

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.JettyJSONContextServer;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.Assert;
import org.junit.Test;

public class JettyCustomSerializationTest extends ClientServerTest {
    @Test
    public void testJettyCustomSerialization() throws Exception {
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put(AbstractServerTransport.JSON_CONTEXT_OPTION, TestJettyJSONContextServer.class.getName());
        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put(ClientTransport.JSON_CONTEXT_OPTION, new TestJettyJSONContextClient());

        start(serverOptions);

        String broadcastChannelName = "/data";
        final String serviceChannelName = "/service/data";
        final String content = "random";
        final CountDownLatch broadcastLatch = new CountDownLatch(1);
        final CountDownLatch serviceLatch = new CountDownLatch(2);

        final LocalSession service = bayeux.newLocalSession("custom_serialization");
        service.handshake();
        service.getChannel(broadcastChannelName).subscribe((channel, message) -> {
            Data data = (Data)message.getData();
            Assert.assertEquals(content, data.content);
            Map<String, Object> ext = message.getExt();
            Assert.assertNotNull(ext);
            Extra extra = (Extra)ext.get("extra");
            Assert.assertEquals(content, extra.content);
            broadcastLatch.countDown();
        });

        bayeux.createChannelIfAbsent(serviceChannelName).getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                Data data = (Data)message.getData();
                Assert.assertEquals(content, data.content);
                Map<String, Object> ext = message.getExt();
                Assert.assertNotNull(ext);
                Extra extra = (Extra)ext.get("extra");
                Assert.assertEquals(content, extra.content);
                serviceLatch.countDown();

                ServerMessage.Mutable mutable = bayeux.newMessage();
                mutable.setChannel(serviceChannelName);
                mutable.setData(new Data(content));
                mutable.getExt(true).put("extra", new Extra(content));
                from.deliver(service, message, Promise.noop());

                return true;
            }
        });

        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(clientOptions, httpClient));
        client.addExtension(new ExtraExtension(content));
        client.getChannel(serviceChannelName).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (!message.isPublishReply()) {
                Data data = (Data)message.getData();
                Assert.assertEquals(content, data.content);
                Map<String, Object> ext = message.getExt();
                Assert.assertNotNull(ext);
                Extra extra = (Extra)ext.get("extra");
                Assert.assertEquals(content, extra.content);
                serviceLatch.countDown();
            }
        });

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        // Wait for the /meta/connect.
        Thread.sleep(1000);

        client.getChannel(broadcastChannelName).publish(new Data(content));
        client.getChannel(serviceChannelName).publish(new Data(content));
        Assert.assertTrue(broadcastLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(serviceLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testParserGenerator() throws Exception {
        JSONContext.Client jsonContext = new TestJettyJSONContextClient();
        Data data1 = new Data("data");
        Extra extra1 = new Extra("extra");
        Map<String, Object> map1 = new HashMap<>();
        map1.put("data", data1);
        map1.put("extra", extra1);
        String json = jsonContext.getGenerator().generate(map1);
        Map map2 = jsonContext.getParser().parse(new StringReader(json), Map.class);
        Data data2 = (Data)map2.get("data");
        Extra extra2 = (Extra)map2.get("extra");
        Assert.assertEquals(data1.content, data2.content);
        Assert.assertEquals(extra1.content, extra2.content);
    }

    private static class ExtraExtension implements ClientSession.Extension {
        private final String content;

        public ExtraExtension(String content) {
            this.content = content;
        }

        @Override
        public boolean send(ClientSession session, Message.Mutable message) {
            Map<String, Object> ext = message.getExt(true);
            ext.put("extra", new Extra(content));
            return true;
        }
    }

    private static class TestJettyJSONContextClient extends JettyJSONContextClient {
        private TestJettyJSONContextClient() {
            getJSON().addConvertor(Data.class, new DataConvertor());
            getJSON().addConvertor(Extra.class, new ExtraConvertor());
        }
    }

    public static class TestJettyJSONContextServer extends JettyJSONContextServer {
        public TestJettyJSONContextServer() {
            getJSON().addConvertor(Data.class, new DataConvertor());
            getJSON().addConvertor(Extra.class, new ExtraConvertor());
        }
    }

    private static class Data {
        private String content;

        private Data(String content) {
            this.content = content;
        }
    }

    private static class Extra {
        private String content;

        private Extra(String content) {
            this.content = content;
        }
    }

    private static class DataConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object object, JSON.Output output) {
            Data data = (Data)object;
            output.addClass(Data.class);
            output.add("content", data.content);
        }

        @Override
        public Object fromJSON(Map map) {
            String content = (String)map.get("content");
            return new Data(content);
        }
    }

    private static class ExtraConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object object, JSON.Output output) {
            Extra extra = (Extra)object;
            output.addClass(Extra.class);
            output.add("content", extra.content);
        }

        @Override
        public Object fromJSON(Map map) {
            String content = (String)map.get("content");
            return new Extra(content);
        }
    }
}
