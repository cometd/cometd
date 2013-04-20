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

package org.cometd.common;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * 
 * Note: to obtain meaningful memory related results from this test you need to ensure that 
 * the garbage collector doesn't get triggered during the execution.
 * 
 * To do that you could run the tests with JVM parameters along the lines of
 * 
 * -Xmx1024m -Xms1024m -XX:MaxNewSize=512m -XX:NewSize=512m
 * 
 * You should also enable verbose garbage collector logging so that you know whether you obtained
 * usable results (no GCs traced) or whether you should repeat the test with different JVM args.
 * 
 * You can still run the test without all of the above if you choose to focus on times and not on 
 * garbage generated.
 */
@RunWith(Parameterized.class)
public class JettyJacksonComparisonTest
{
	
	@Parameters(name= "{index}: JSON Provider: {0} Iterations: {1} Count: {2}")
 	public static Iterable<Object[]> data() 
 	{
 		return Arrays.asList(new Object[][] 
 				{ 
 					{ Jackson1JSONProvider.class, 5, 1000 }, 
 					{ Jackson2JSONProvider.class, 5, 1000 },
 					{ JettyJSONProvider.class, 5, 1000 }
 				}
 		);
     }

	private interface JSONProvider 
	{
		HashMapMessage[] readValueAs(String json) throws Exception;	
		
		HashMapMessage[] readValue(String json) throws Exception;
		
		HashMapMessage[] readValue(byte[] json) throws Exception;
		
		Message.Mutable[] parse(String json) throws Exception;
		
		void writeObject(Object pojo) throws Exception;
		
		String generate(Message.Mutable message) throws Exception;
		
		String writeValueAsString(Object value) throws Exception;
		
		String getProviderKey();
		
		boolean supportsContextClientOnly();
	}
	
	private static class Jackson1JSONProvider implements JSONProvider 
	{
		private final org.codehaus.jackson.map.ObjectMapper jackson1ObjectMapper = new org.codehaus.jackson.map.ObjectMapper();
		private final org.codehaus.jackson.type.JavaType jackson1Type = jackson1ObjectMapper.constructType(HashMapMessage[].class);
		private final org.codehaus.jackson.JsonFactory json1Factory = new org.codehaus.jackson.map.MappingJsonFactory();
		private final Jackson1JSONContextClient jackson1ContextClient = new Jackson1JSONContextClient();
		
		Jackson1JSONProvider()
		{
			super();
		}
		
		@Override
        public HashMapMessage[] readValueAs(String json) throws Exception
		{
			org.codehaus.jackson.JsonParser jsonParser = json1Factory.createJsonParser(json);
			HashMapMessage[] value = jsonParser.readValueAs(HashMapMessage[].class);
            jsonParser.close();
            return value;
        }

		@Override
        public HashMapMessage[] readValue(String json) throws Exception 
        {
			return jackson1ObjectMapper.readValue(json, jackson1Type);
        }
		
		@Override
        public HashMapMessage[] readValue(byte[] json) throws Exception 
        {
	        return jackson1ObjectMapper.readValue(json, jackson1Type);
        }

		@Override
        public Message.Mutable[] parse(String json) throws Exception 
        {
	        return jackson1ContextClient.parse(json);
        }

		@Override
        public void writeObject(Object pojo) throws Exception 
        {
			org.codehaus.jackson.JsonGenerator jsonGenerator = json1Factory.createJsonGenerator(new StringWriter(384));
            jsonGenerator.writeObject(pojo);
            jsonGenerator.close();
        }

		@Override
        public String generate(Message.Mutable message) throws Exception 
        {
	        return jackson1ContextClient.generate(message);
        }

		@Override
        public String writeValueAsString(Object value) throws Exception 
        {
	        return jackson1ObjectMapper.writeValueAsString(value);
        }
		
		@Override
        public String getProviderKey() 
		{
	        return "jackson1";
        }
		
		@Override
        public boolean supportsContextClientOnly() 
		{
	        return false;
        }
		
	}
	
	private static class Jackson2JSONProvider implements JSONProvider
	{
		private final com.fasterxml.jackson.core.JsonFactory json2Factory = new com.fasterxml.jackson.databind.MappingJsonFactory();
		private final com.fasterxml.jackson.databind.ObjectMapper jackson2ObjectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
		private final com.fasterxml.jackson.databind.JavaType jackson2Type = jackson2ObjectMapper.constructType(HashMapMessage[].class);
		private final Jackson2JSONContextClient jackson2ContextClient = new Jackson2JSONContextClient();
		
		Jackson2JSONProvider()
		{
			super();
		}
		
		@Override
        public HashMapMessage[] readValueAs(String json) throws Exception 
        {
			com.fasterxml.jackson.core.JsonParser jsonParser = json2Factory.createJsonParser(json);
            HashMapMessage[] value = jsonParser.readValueAs(HashMapMessage[].class);
            jsonParser.close();
            return value;
        }

		@Override
        public HashMapMessage[] readValue(String json) throws Exception 
        {
	        return jackson2ObjectMapper.readValue(json, jackson2Type);
        }
		
		@Override
        public HashMapMessage[] readValue(byte[] json) throws Exception 
        {
	        return jackson2ObjectMapper.readValue(json, jackson2Type);
        }

		@Override
        public org.cometd.bayeux.Message.Mutable[] parse(String json) throws Exception 
        {
	        return jackson2ContextClient.parse(json);
        }
		
		@Override
        public void writeObject(Object pojo) throws Exception 
        {
			com.fasterxml.jackson.core.JsonGenerator jsonGenerator = json2Factory.createJsonGenerator(new StringWriter(384));
            jsonGenerator.writeObject(pojo);
            jsonGenerator.close();
        }

		@Override
        public String generate(Message.Mutable message) throws Exception 
        {
	        return jackson2ContextClient.generate(message);
        }

		@Override
        public String writeValueAsString(Object value) throws Exception 
        {
	        return jackson2ObjectMapper.writeValueAsString(value);
        }
		
		@Override
        public String getProviderKey() 
		{
	        return "jackson2";
        }
		
		@Override
        public boolean supportsContextClientOnly() 
		{
	        return false;
        }
	}
	
	private static final class JettyJSONProvider implements JSONProvider
	{
		private JettyJSONContextClient jettyJSONContextClient = new JettyJSONContextClient();
		
		JettyJSONProvider()
		{
			super();
		}
		
		@Override
        public HashMapMessage[] readValueAs(String json) throws Exception 
        {
			throw new UnsupportedOperationException();
        }

		@Override
        public HashMapMessage[] readValue(String json) throws Exception 
        {
			throw new UnsupportedOperationException();
        }

		@Override
        public HashMapMessage[] readValue(byte[] json) throws Exception 
        {
	        throw new UnsupportedOperationException();
        }

		@Override
        public Mutable[] parse(String json) throws Exception 
        {
	        return jettyJSONContextClient.parse(json);
        }
		

		@Override
        public void writeObject(Object pojo) throws Exception 
        {
			throw new UnsupportedOperationException();
        }

		@Override
        public String generate(Message.Mutable message) throws Exception 
        {
	        return jettyJSONContextClient.generate(message);
        }

		@Override
        public String writeValueAsString(Object value) throws Exception 
        {
			throw new UnsupportedOperationException();
        }

		@Override
        public String getProviderKey() 
		{
	        return "jetty";
        }

		@Override
        public boolean supportsContextClientOnly() 
		{
	        return true;
        }
	}
	
	
	private final JSONProvider jsonProvider;
	private final int iterations;
	private final int count;
	
	public JettyJacksonComparisonTest(final Object jsonProvider, final Integer iterations, final Integer count) throws Exception
	{
		this.jsonProvider =  (JSONProvider) ((Class<?>) jsonProvider).newInstance();
		this.iterations = iterations.intValue();
		this.count = count.intValue();
	}
	
    @Test
    public void testParse() throws Exception
    {
        String json = "" +
                "[{" +
                "   \"successful\":true," +
                "   \"id\":\"1\"," +
                "   \"clientId\":\"abcdefghijklmnopqrstuvwxyz\"," +
                "   \"channel\":\"/meta/connect\"," +
                "   \"data\":{" +
                "       \"peer\":\"bar\"," +
                "       \"chat\":\"woot\"," +
                "       \"user\":\"foo\"," +
                "       \"room\":\"abc\"" +
                "   }," +
                "   \"advice\":{" +
                "       \"timeout\":0" +
                "   }," +
                "   \"ext\":{" +
                "       \"com.acme.auth\":{" +
                "           \"token\":\"0123456789\"" +
                "       }" +
                "   }" +
                "}]";

        for (int j = 0; j < iterations; ++j)
        {
        	System.gc();
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
            	jsonProvider.parse(json);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: "+jsonProvider.getProviderKey()+" test: context_parse iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }
        
        if (jsonProvider.supportsContextClientOnly())
        {
        	return;
        }

        for (int j = 0; j < iterations; ++j)
        {
        	System.gc();
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            
            for (int i = 0; i < count; ++i)
            {
            	jsonProvider.readValueAs(json);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            
            System.err.printf("library: "+jsonProvider.getProviderKey()+" test: parser_readValueAs iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        
        for (int j = 0; j < iterations; ++j)
        {
        	System.gc();
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
            	jsonProvider.readValue(json);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: "+jsonProvider.getProviderKey()+" test: mapper_readValue_string iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        byte[] bytes = json.getBytes("UTF-8");
        
        for (int j = 0; j < iterations; ++j)
        {
        	System.gc();
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
            	jsonProvider.readValue(bytes);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: "+jsonProvider.getProviderKey()+" test: mapper_readValue_bytes iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }
    }

    @Test
    public void testGenerate() throws Exception
    {
        HashMapMessage message = new HashMapMessage();
        message.setChannel("/meta/connect");
        message.setClientId("abcdefghijklmnopqrstuvwxyz");
        message.setId("1");
        message.setSuccessful(true);
        Map<String, Object> data = new HashMap<String, Object>();
        message.setData(data);
        data.put("user", "foo");
        data.put("room", "abc");
        data.put("peer", "bar");
        data.put("chat", "woot");
        Map<String, Object> advice = message.getAdvice(true);
        advice.put("timeout", 0);
        Map<String, Object> ext = message.getExt(true);
        Map<String, Object> acmeExt = new HashMap<String, Object>();
        ext.put("com.acme.auth", acmeExt);
        acmeExt.put("token", "0123456789");

        for (int j = 0; j < iterations; ++j)
        {
        	System.gc();
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                jsonProvider.generate(message);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: "+jsonProvider.getProviderKey()+" test: context_generate iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }
        
        if (jsonProvider.supportsContextClientOnly())
        {
        	return;
        }

        for (int j = 0; j < iterations; ++j)
        {
        	System.gc();
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                jsonProvider.writeObject(message);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: "+jsonProvider.getProviderKey()+" test: generator_writeObject iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        for (int j = 0; j < iterations; ++j)
        {
        	System.gc();
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
            	jsonProvider.writeValueAsString(message);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: "+jsonProvider.getProviderKey()+" test: mapper_writeValueAsString iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }
    }
}
