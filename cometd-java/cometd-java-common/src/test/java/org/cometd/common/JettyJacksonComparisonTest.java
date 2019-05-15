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
package org.cometd.common;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public class JettyJacksonComparisonTest {
    @Parameters(name = "{index}: JSON Provider: {0} Iterations: {1} Count: {2}")
    public static Iterable<Object[]> data() {
        return Arrays.asList(new Object[][]
                {
                        {JacksonJSONProvider.class},
                        {JettyJSONProvider.class}
                }
        );
    }

    public interface JSONProvider {
        String getName();

        Message.Mutable[] parse(String json) throws Exception;

        String generate(Message.Mutable message) throws Exception;
    }

    public static class JacksonJSONProvider implements JSONProvider {
        private final JacksonJSONContextClient jacksonContextClient = new JacksonJSONContextClient();

        @Override
        public String getName() {
            return "jackson";
        }

        @Override
        public org.cometd.bayeux.Message.Mutable[] parse(String json) throws Exception {
            return jacksonContextClient.parse(json);
        }

        @Override
        public String generate(Message.Mutable message) throws Exception {
            return jacksonContextClient.generate(message);
        }
    }

    public static final class JettyJSONProvider implements JSONProvider {
        private JettyJSONContextClient jettyJSONContextClient = new JettyJSONContextClient();

        @Override
        public String getName() {
            return "jetty";
        }

        @Override
        public Mutable[] parse(String json) throws Exception {
            return jettyJSONContextClient.parse(json);
        }

        @Override
        public String generate(Message.Mutable message) throws Exception {
            return jettyJSONContextClient.generate(message);
        }
    }

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final JSONProvider jsonProvider;

    public JettyJacksonComparisonTest(final Class<?> jsonProvider) throws Exception {
        this.jsonProvider = (JSONProvider)jsonProvider.getConstructor().newInstance();
    }

    @Test
    public void testParse() throws Exception {
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

        for (int j = 0; j < iterations; ++j) {
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i) {
                jsonProvider.parse(json);
            }
            long end = System.nanoTime();
            logger.info("{} context_parse iteration: {} time: {} ms",
                    jsonProvider.getName(),
                    j,
                    TimeUnit.NANOSECONDS.toMillis(end - start));
        }
    }

    @Test
    public void testGenerate() throws Exception {
        HashMapMessage message = new HashMapMessage();
        message.setChannel("/meta/connect");
        message.setClientId("abcdefghijklmnopqrstuvwxyz");
        message.setId("1");
        message.setSuccessful(true);
        Map<String, Object> data = new HashMap<>();
        message.setData(data);
        data.put("user", "foo");
        data.put("room", "abc");
        data.put("peer", "bar");
        data.put("chat", "woot");
        Map<String, Object> advice = message.getAdvice(true);
        advice.put("timeout", 0);
        Map<String, Object> ext = message.getExt(true);
        Map<String, Object> acmeExt = new HashMap<>();
        ext.put("com.acme.auth", acmeExt);
        acmeExt.put("token", "0123456789");

        int iterations = 5;
        int count = 200000;

        for (int j = 0; j < iterations; ++j) {
            long start = System.nanoTime();
            for (int i = 0; i < count; ++i) {
                jsonProvider.generate(message);
            }
            long end = System.nanoTime();
            logger.info("{} context_generate iteration: {} time: {} ms",
                    jsonProvider.getName(),
                    j,
                    TimeUnit.NANOSECONDS.toMillis(end - start));
        }
    }
}
