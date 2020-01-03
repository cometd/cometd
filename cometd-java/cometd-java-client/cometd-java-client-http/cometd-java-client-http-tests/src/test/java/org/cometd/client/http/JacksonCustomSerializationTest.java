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
package org.cometd.client.http;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.JSONContext;
import org.cometd.common.JacksonJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.JacksonJSONContextServer;
import org.cometd.server.http.AbstractHttpTransport;
import org.junit.Assert;
import org.junit.Test;

public class JacksonCustomSerializationTest extends ClientServerTest {
    @Test
    public void testJacksonCustomSerialization() throws Exception {
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put(AbstractServerTransport.JSON_CONTEXT_OPTION, TestJacksonJSONContextServer.class.getName());
        serverOptions.put(AbstractHttpTransport.JSON_DEBUG_OPTION, "true");
        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put(ClientTransport.JSON_CONTEXT_OPTION, TestJacksonJSONContextClient.class.getName());

        start(serverOptions);

        String channelName = "/data";
        final String dataContent = "random";
        final long extraContent = 13;
        final CountDownLatch latch = new CountDownLatch(1);

        LocalSession service = bayeux.newLocalSession("custom_serialization");
        service.handshake();
        service.getChannel(channelName).subscribe((channel, message) -> {
            Data data = (Data)message.getData();
            Assert.assertEquals(dataContent, data.content);
            Map<String, Object> ext = message.getExt();
            Assert.assertNotNull(ext);
            Extra extra = (Extra)ext.get("extra");
            Assert.assertEquals(extraContent, extra.content);
            latch.countDown();
        });

        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(clientOptions, httpClient));
        client.addExtension(new ExtraExtension(extraContent));

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        // Wait for the connect to establish
        Thread.sleep(1000);

        client.getChannel(channelName).publish(new Data(dataContent));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testParserGenerator() throws Exception {
        // Note: Jackson does not seem to be able to serialize/deserialize correctly a single Data/Extra object.
        // However, if they are put into a container like a Map, then Jackson produces a different JSON than
        // what it produces for the standalone object that allows correct deserialization, of this form:
        // { field: ["className", {object}] }
        // It is way easier to have Jetty serialize and deserialize this form than make Jackson use Jetty's form.
        // They problem is that Jackson tries to be "smart" in figuring out the typing, but with a Map<String, Object>
        // there is no way to have type information for the values, so Jackson defaults to a basic deserializer
        // that either is not very flexible, or it's very difficult to configure, so much that I could not so far.

        JSONContext.Client jsonContext = new TestJacksonJSONContextClient();
        Data data1 = new Data("data");
        Extra extra1 = new Extra(42L);
        Map<String, Object> map1 = new HashMap<>();
        map1.put("data", data1);
        map1.put("extra", extra1);
        String json = jsonContext.getGenerator().generate(map1);
        @SuppressWarnings("unchecked")
        Map<String, Object> map2 = jsonContext.getParser().parse(new StringReader(json), Map.class);
        Data data2 = (Data)map2.get("data");
        Extra extra2 = (Extra)map2.get("extra");
        Assert.assertEquals(data1.content, data2.content);
        Assert.assertEquals(extra1.content, extra2.content);
    }

    private static class ExtraExtension implements ClientSession.Extension {
        private final long content;

        public ExtraExtension(long content) {
            this.content = content;
        }

        @Override
        public boolean send(ClientSession session, Message.Mutable message) {
            Map<String, Object> ext = message.getExt(true);
            ext.put("extra", new Extra(content));
            return true;
        }
    }

    public static class TestJacksonJSONContextServer extends JacksonJSONContextServer {
        public TestJacksonJSONContextServer() {
            getObjectMapper().enableDefaultTyping(ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT);
        }
    }

    public static class TestJacksonJSONContextClient extends JacksonJSONContextClient {
        public TestJacksonJSONContextClient() {
            getObjectMapper().enableDefaultTyping(ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT);
        }
    }

    private static class Data {
        @com.fasterxml.jackson.annotation.JsonProperty
        private String content;

        private Data() {
            // Needed by Jackson
        }

        private Data(String content) {
            this.content = content;
        }
    }

    private static class Extra {
        @com.fasterxml.jackson.annotation.JsonProperty
        private long content;

        private Extra() {
            // Needed by Jackson
        }

        private Extra(long content) {
            this.content = content;
        }
    }
}
