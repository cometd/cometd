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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * 
 * Note: to obtain meaningful memory related results from this test you need to ensure that 
 * the garbage collector doesn't get triggered.
 * 
 * To do that you could run the tests with JVM parameters along the lines of
 * 
 * -Xmx1024m -Xms1024m -XX:MaxNewSize=512m -XX:NewSize=512m
 * 
 * You should also enable verbose garbage collector logging so that you know whether you obtained
 * usable results (no GCs traced) or whether you should repeat the test with different JVM args.
 * 
 */
public class JettyJacksonComparisonTest
{
	
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

        int iterations = 5;
        int count = 100000;

        // Jackson 2
        com.fasterxml.jackson.core.JsonFactory jsonFactory = new com.fasterxml.jackson.databind.MappingJsonFactory();
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            
            for (int i = 0; i < count; ++i)
            {
            	com.fasterxml.jackson.core.JsonParser jsonParser = jsonFactory.createJsonParser(json);
                jsonParser.readValueAs(HashMapMessage[].class);
                jsonParser.close();
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            
            System.err.printf("library: jackson2 test: parser_readValueAs iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        // Jackson 2
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JavaType type = objectMapper.constructType(HashMapMessage[].class);
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                objectMapper.readValue(json, type);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson2 test: mapper_readValue_string iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        // Jackson 2
        byte[] bytes = json.getBytes("UTF-8");
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                objectMapper.readValue(bytes, type);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson2 test: mapper_readValue_bytes iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        // Jackson 2
        Jackson2JSONContextClient jacksonJSONContextClient = new Jackson2JSONContextClient();
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                jacksonJSONContextClient.parse(json);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson2 test: context_parse iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }
        
        
        // Jackson 1
        org.codehaus.jackson.JsonFactory json1Factory = new org.codehaus.jackson.map.MappingJsonFactory();
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            
            for (int i = 0; i < count; ++i)
            {
            	org.codehaus.jackson.JsonParser jsonParser = json1Factory.createJsonParser(json);
                jsonParser.readValueAs(HashMapMessage[].class);
                jsonParser.close();
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            
            System.err.printf("library: jackson1 test: parser_readValueAs iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        // Jackson 1
        org.codehaus.jackson.map.ObjectMapper jackson1objectMapper = new org.codehaus.jackson.map.ObjectMapper();
        org.codehaus.jackson.type.JavaType jackson1type = jackson1objectMapper.constructType(HashMapMessage[].class);
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
            	jackson1objectMapper.readValue(json, jackson1type);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson1 test: mapper_readValue_string iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        // Jackson 1
        bytes = json.getBytes("UTF-8");
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
            	jackson1objectMapper.readValue(bytes, jackson1type);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson1 test: mapper_readValue_bytes iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        // Jackson 1
        Jackson1JSONContextClient jackson1JSONContextClient = new Jackson1JSONContextClient();
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
            	jackson1JSONContextClient.parse(json);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson1 test: context_parse iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        // Jetty
        JettyJSONContextClient jettyJSONContextClient = new JettyJSONContextClient();
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                jettyJSONContextClient.parse(json);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jetty test: context_parse iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
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

        int iterations = 5;
        int count = 200000;

        // Jackson 2
        com.fasterxml.jackson.core.JsonFactory jsonFactory = new com.fasterxml.jackson.databind.MappingJsonFactory();
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
            	com.fasterxml.jackson.core.JsonGenerator jsonGenerator = jsonFactory.createJsonGenerator(new StringWriter(384));
                jsonGenerator.writeObject(message);
                jsonGenerator.close();
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson2 test: generator_writeObject iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        // Jackson 2
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                objectMapper.writeValueAsString(message);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson2 test: mapper_writeValueAsString iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        // Jackson 2
        Jackson2JSONContextClient jacksonJSONContextClient = new Jackson2JSONContextClient();
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                jacksonJSONContextClient.generate(message);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson2 test: context_generate iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        
        
        
        
        
        // Jackson 1
        org.codehaus.jackson.JsonFactory json1Factory = new org.codehaus.jackson.map.MappingJsonFactory();
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
            	org.codehaus.jackson.JsonGenerator jsonGenerator = json1Factory.createJsonGenerator(new StringWriter(384));
                jsonGenerator.writeObject(message);
                jsonGenerator.close();
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson1 test: generator_writeObject iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        // Jackson 1
        org.codehaus.jackson.map.ObjectMapper jackson1objectMapper = new org.codehaus.jackson.map.ObjectMapper();
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
            	jackson1objectMapper.writeValueAsString(message);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson1 test: mapper_writeValueAsString iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }

        // Jackson 1
        Jackson1JSONContextClient jackson1JSONContextClient = new Jackson1JSONContextClient();
        
        
        for (int j = 0; j < iterations; ++j)
        {
        	
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
            	jackson1JSONContextClient.generate(message);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jackson1 test: context_generate iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }
        
        // Jetty
        JettyJSONContextClient jettyJSONContextClient = new JettyJSONContextClient();
        
        
        for (int j = 0; j < iterations; ++j)
        {
            long freeMemBefore = Runtime.getRuntime().freeMemory();
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                jettyJSONContextClient.generate(message);
            }
            long end = System.nanoTime();
            long generatedGarbage = freeMemBefore-Runtime.getRuntime().freeMemory();
            System.err.printf("library: jetty test: context_generate iteration: %d time: %d ms garbage: %d%n", j, TimeUnit.NANOSECONDS.toMillis(end - start), generatedGarbage);
        }
    }
}
