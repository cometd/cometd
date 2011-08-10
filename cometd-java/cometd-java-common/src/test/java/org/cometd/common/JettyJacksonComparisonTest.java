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

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.MappingJsonFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Test;

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

        // Jackson
        JsonFactory jsonFactory = new MappingJsonFactory();
        for (int j = 0; j < iterations; ++j)
        {
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                JsonParser jsonParser = jsonFactory.createJsonParser(json);
                jsonParser.readValueAs(HashMapMessage[].class);
                jsonParser.close();
            }
            long end = System.nanoTime();
            System.err.printf("jackson parser iteration %d: %d ms%n", j, TimeUnit.NANOSECONDS.toMillis(end - start));
        }

        // Jackson
        ObjectMapper objectMapper = new ObjectMapper();
        for (int j = 0; j < iterations; ++j)
        {
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                objectMapper.readValue(json, HashMapMessage[].class);
            }
            long end = System.nanoTime();
            System.err.printf("jackson mapper iteration %d: %d ms%n", j, TimeUnit.NANOSECONDS.toMillis(end - start));
        }

        // Jackson
        JacksonJSONContextClient jacksonJSONContextClient = new JacksonJSONContextClient();
        for (int j = 0; j < iterations; ++j)
        {
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                jacksonJSONContextClient.parse(json);
            }
            long end = System.nanoTime();
            System.err.printf("jackson context iteration %d: %d ms%n", j, TimeUnit.NANOSECONDS.toMillis(end - start));
        }

        // Jetty
        JettyJSONContextClient jettyJSONContextClient = new JettyJSONContextClient();
        for (int j = 0; j < iterations; ++j)
        {
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                jettyJSONContextClient.parse(json);
            }
            long end = System.nanoTime();
            System.err.printf("jetty iteration %d: %d ms%n", j, TimeUnit.NANOSECONDS.toMillis(end - start));
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

        // Jackson
        JsonFactory jsonFactory = new MappingJsonFactory();
        for (int j = 0; j < iterations; ++j)
        {
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                JsonGenerator jsonGenerator = jsonFactory.createJsonGenerator(new StringWriter(384));
                jsonGenerator.writeObject(message);
                jsonGenerator.close();
            }
            long end = System.nanoTime();
            System.err.printf("jackson generator iteration %d: %d ms%n", j, TimeUnit.NANOSECONDS.toMillis(end - start));
        }

        // Jackson
        ObjectMapper objectMapper = new ObjectMapper();
        for (int j = 0; j < iterations; ++j)
        {
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                objectMapper.writeValueAsString(message);
            }
            long end = System.nanoTime();
            System.err.printf("jackson mapper iteration %d: %d ms%n", j, TimeUnit.NANOSECONDS.toMillis(end - start));
        }

        // Jackson
        JacksonJSONContextClient jacksonJSONContextClient = new JacksonJSONContextClient();
        for (int j = 0; j < iterations; ++j)
        {
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                jacksonJSONContextClient.generate(message);
            }
            long end = System.nanoTime();
            System.err.printf("jackson context iteration %d: %d ms%n", j, TimeUnit.NANOSECONDS.toMillis(end - start));
        }

        // Jetty
        JettyJSONContextClient jettyJSONContextClient = new JettyJSONContextClient();
        for (int j = 0; j < iterations; ++j)
        {
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i)
            {
                jettyJSONContextClient.generate(message);
            }
            long end = System.nanoTime();
            System.err.printf("jetty iteration %d: %d ms%n", j, TimeUnit.NANOSECONDS.toMillis(end - start));
        }
    }
}
