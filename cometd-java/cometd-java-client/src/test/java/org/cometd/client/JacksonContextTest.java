/*
 * Copyright (c) 2011 the original author or authors.
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

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.Version;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.annotate.JsonTypeInfo;
import org.codehaus.jackson.map.BeanProperty;
import org.codehaus.jackson.map.ContextualDeserializer;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.DeserializationContext;
import org.codehaus.jackson.map.JsonDeserializer;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.JsonSerializer;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializerProvider;
import org.codehaus.jackson.map.deser.UntypedObjectDeserializer;
import org.codehaus.jackson.map.module.SimpleModule;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.HashMapMessage;
import org.cometd.common.JacksonJSONContextClient;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.JacksonJSONContextServer;
import org.cometd.server.ServerMessageImpl;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class JacksonContextTest extends ClientServerTest
{
    @Test
    public void testAllMessagesUseJackson() throws Exception
    {
        Map<String, String> serverParams = new HashMap<String, String>();
        serverParams.put(BayeuxServerImpl.JSON_CONTEXT, JacksonJSONContextServer.class.getName());
        startServer(serverParams);

        Map<String, Object> clientParams = new HashMap<String, Object>();
        clientParams.put(ClientTransport.JSON_CONTEXT, new JacksonJSONContextClient());
        final BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(clientParams, httpClient));
        client.setDebugEnabled(debugTests());

        client.handshake();
        Assert.assertTrue(client.waitFor(1000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(500);

        final String channelName = "/test_jackson";
        final CountDownLatch localLatch = new CountDownLatch(2);
        new AbstractService(bayeux, channelName)
        {
            {
                addService(channelName, "process");

                getLocalSession().getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
                {
                    private boolean republishSeen;

                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        System.err.println("local message = " + message);
                        Map<String, Object> data = message.getDataAsMap();
                        Assert.assertTrue(data.containsKey("publish"));
                        republishSeen |= data.containsKey("republish");
                        if (localLatch.getCount() == 1 && !republishSeen)
                            Assert.fail();
                        localLatch.countDown();
                    }
                });
            }

            public void process(ServerSession session, ServerMessage message)
            {
                // Republish
                Map<String, Object> data = message.getDataAsMap();
                Map<String, Object> republishData = new HashMap<String, Object>(data);
                republishData.put("republish", true);
                getBayeux().getChannel(channelName).publish(getServerSession(), republishData, null);
                // Deliver
                Map<String, Object> deliverData = new HashMap<String, Object>(data);
                deliverData.put("deliver", true);
                session.deliver(getServerSession(), channelName, deliverData, null);
            }
        };

        // Clear out the jsonContexts embedded in the message classes
        Field clientJSONContext = HashMapMessage.class.getDeclaredField("_jsonContext");
        clientJSONContext.setAccessible(true);
        clientJSONContext.set(null, null);
        Field serverJSONContext = ServerMessageImpl.class.getDeclaredField("_jsonContext");
        serverJSONContext.setAccessible(true);
        serverJSONContext.set(null, null);

        final ClientSessionChannel channel = client.getChannel(channelName);
        final CountDownLatch clientLatch = new CountDownLatch(3);
        client.batch(new Runnable()
        {
            public void run()
            {
                channel.subscribe(new ClientSessionChannel.MessageListener()
                {
                    private boolean republishSeen;
                    private boolean deliverSeen;

                    public void onMessage(ClientSessionChannel channel, Message message)
                    {
                        System.err.println("message = " + message);
                        Map<String, Object> data = message.getDataAsMap();
                        Assert.assertTrue(data.containsKey("publish"));
                        republishSeen |= data.containsKey("republish");
                        deliverSeen |= data.containsKey("deliver");
                        if (clientLatch.getCount() == 1 && !republishSeen && !deliverSeen)
                            Assert.fail();
                        clientLatch.countDown();
                    }
                });
                Map<String, Object> data = new HashMap<String, Object>();
                data.put("publish", true);
                channel.publish(data);
            }
        });

        Assert.assertTrue(localLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Ignore
    @Test
    public void testJacksonCustomSerialization() throws Exception
    {
        TestJacksonJSONContextClient jsonContext = new TestJacksonJSONContextClient();

        HashMapMessage message = new HashMapMessage();
        message.setChannel("/foo");
        message.setData(new ByteArray(new byte[]{0, 0}));

        ByteArray object = new ByteArray(new byte[]{0, 0});

        String json = jsonContext.getObjectMapper().writeValueAsString(object);
        System.err.println("json = " + json);
    }

    public static class  TestJacksonJSONContextClient extends JacksonJSONContextClient
    {
        public TestJacksonJSONContextClient()
        {
            ObjectMapper objectMapper = getObjectMapper();
            objectMapper.registerModule(new TestModule());
        }

        @Override
        public ObjectMapper getObjectMapper()
        {
            return super.getObjectMapper();
        }
    }

    public static class TestJacksonJSONContextServer extends JacksonJSONContextServer
    {
        public TestJacksonJSONContextServer()
        {
            ObjectMapper objectMapper = getObjectMapper();
            objectMapper.registerModule(new TestModule());
        }
    }

    public static class TestModule extends SimpleModule
    {
        public TestModule()
        {
            super("test", Version.unknownVersion());
//            addSerializer(ByteArray.class, new ByteArraySerializer());
//            addDeserializer(Object.class, new ByteArrayDeserializer());
        }

        @Override
        public void setupModule(SetupContext context)
        {
            super.setupModule(context);
            context.setMixInAnnotations(Message.class, MessageMixIn.class);
            context.setMixInAnnotations(ByteArray.class, ByteArrayMixIn.class);
        }
    }

    public static class ByteArray
    {
        private final byte[] bytes;

        public ByteArray(byte[] bytes)
        {
            this.bytes = bytes;
        }

        public byte[] getBytes()
        {
            return bytes;
        }
    }

    public static abstract class ByteArrayMixIn
    {
        @JsonProperty("object")
        @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
        public abstract Object getObject();
    }

    public static abstract class MessageMixIn
    {
        @JsonProperty("data")
        @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
        public abstract Object getData();
    }


    public static class ByteArraySerializer extends JsonSerializer<ByteArray>
    {
        @Override
        public void serialize(ByteArray byteArray, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException, JsonProcessingException
        {
//            char[] encoded = B64Code.encode(byteArray.bytes);
//            jsonGenerator.writeStartObject();
//            jsonGenerator.writeStringField("@class", byteArray.getClass().getName());
//            jsonGenerator.writeFieldName("data");
//            jsonGenerator.writeString(encoded, 0, encoded.length);
//            jsonGenerator.writeEndObject();
        }
    }

    public static class ByteArrayDeserializer extends JsonDeserializer<ByteArray> implements ContextualDeserializer<Object>
    {
        public ByteArrayDeserializer()
        {
            System.err.println("deserializer = " + this);
        }

        @Override
        public ByteArray deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JsonProcessingException
        {
            return null;
        }

        public JsonDeserializer<Object> createContextual(DeserializationConfig config, BeanProperty property) throws JsonMappingException
        {
            return new UntypedObjectDeserializer();
        }
    }
}
