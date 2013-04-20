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

import java.util.Arrays;
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
import org.cometd.common.Jackson1JSONContextClient;
import org.cometd.common.Jackson2JSONContextClient;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.Jackson1JSONContextServer;
import org.cometd.server.Jackson2JSONContextServer;
import org.cometd.server.transport.HttpTransport;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class JacksonCustomSerializationTest extends ClientServerTest
{
	@Parameters(name= "{index}: Jackson Context Server: {0} Jackson Context Client: {1}")
 	public static Iterable<Object[]> data() 
 	{
 		return Arrays.asList(new Object[][] 
 				{ 
 					{ TestJackson2JSONContextServer.class, TestJackson2JSONContextClient.class }, 
 					{ TestJackson1JSONContextServer.class, TestJackson1JSONContextClient.class },
 				}
 		);
     }

	private final String jacksonContextServerClassName;
	private final String jacksonContextClientClassName;

	public JacksonCustomSerializationTest(final Object jacksonContextServerClass, final Object jacksonContextClientClass) 
	{
		this.jacksonContextServerClassName = ((Class<?>) jacksonContextServerClass).getName();
		this.jacksonContextClientClassName =  ((Class<?>) jacksonContextClientClass).getName();
	}
	
    @Test
    public void testJacksonCustomSerialization() throws Exception
    {
        Map<String, String> serverOptions = new HashMap<>();
        serverOptions.put(BayeuxServerImpl.JSON_CONTEXT, jacksonContextServerClassName);
        serverOptions.put(HttpTransport.JSON_DEBUG_OPTION, "true");
        Map<String, Object> clientOptions = new HashMap<>();
        clientOptions.put(ClientTransport.JSON_CONTEXT, jacksonContextClientClassName);

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

    public static class TestJackson1JSONContextServer extends Jackson1JSONContextServer
    {
        public TestJackson1JSONContextServer()
        {
            // Configuration needed to work with de/serializers
//            SimpleModule module = new SimpleModule("test", Version.unknownVersion());
//            module.addSerializer(Data.class, new DataSerializer());
//            module.addDeserializer(Object.class, new DataDeserializer());
//            getObjectMapper().registerModule(module);

            getObjectMapper().enableDefaultTyping(org.codehaus.jackson.map.ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT);
        }
    }

    public static class TestJackson1JSONContextClient extends Jackson1JSONContextClient
    {
        public TestJackson1JSONContextClient()
        {
            // Configuration needed to work with de/serializers
//            SimpleModule module = new SimpleModule("test", Version.unknownVersion());
//            module.addSerializer(Data.class, new DataSerializer());
//            module.addDeserializer(Object.class, new DataDeserializer());
//            getObjectMapper().registerModule(module);

            getObjectMapper().enableDefaultTyping(org.codehaus.jackson.map.ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT);
        }
    }
    
    public static class TestJackson2JSONContextServer extends Jackson2JSONContextServer
    {
        public TestJackson2JSONContextServer()
        {
            // Configuration needed to work with de/serializers
//            SimpleModule module = new SimpleModule("test", Version.unknownVersion());
//            module.addSerializer(Data.class, new DataSerializer());
//            module.addDeserializer(Object.class, new DataDeserializer());
//            getObjectMapper().registerModule(module);

            getObjectMapper().enableDefaultTyping(com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT);
        }
    }

    public static class TestJackson2JSONContextClient extends Jackson2JSONContextClient
    {
        public TestJackson2JSONContextClient()
        {
            // Configuration needed to work with de/serializers
//            SimpleModule module = new SimpleModule("test", Version.unknownVersion());
//            module.addSerializer(Data.class, new DataSerializer());
//            module.addDeserializer(Object.class, new DataDeserializer());
//            getObjectMapper().registerModule(module);

            getObjectMapper().enableDefaultTyping(com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT);
        }
    }

    private static class Data
    {
        @com.fasterxml.jackson.annotation.JsonProperty
        @org.codehaus.jackson.annotate.JsonProperty
        private String content;

        private Data()
        {
            // Needed by Jackson
        }

        private Data(String content)
        {
            this.content = content;
        }
    }

    private static class Extra
    {
    	@com.fasterxml.jackson.annotation.JsonProperty
        @org.codehaus.jackson.annotate.JsonProperty
        private String content;

        private Extra()
        {
            // Needed by Jackson
        }

        private Extra(String content)
        {
            this.content = content;
        }
    }

    /* Alternative way to make it working (see above SimpleModule configuration)

    private static class DataSerializer extends JsonSerializer<Data>
    {
        @Override
        public void serialize(Data data, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException, JsonProcessingException
        {
            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField("class", Data.class.getName());
            jsonGenerator.writeStringField("content", data.content);
            jsonGenerator.writeEndObject();
        }
    }

    private static class DataDeserializer extends UntypedObjectDeserializer
    {
        @Override
        public Object deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException, JsonProcessingException
        {
            Object object = super.deserialize(jsonParser, context);
            if (object instanceof Map)
            {
                Map<String, Object> map = (Map<String, Object>)object;
                if (Data.class.getName().equals(map.get("class")))
                    return new Data((String)map.get("content"));
            }
            return object;
        }
    }
    */
}
