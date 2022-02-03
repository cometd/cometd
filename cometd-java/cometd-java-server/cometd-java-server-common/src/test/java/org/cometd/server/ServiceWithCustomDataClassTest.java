/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.server;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ServiceWithCustomDataClassTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testServiceWithCustomDataClass(String serverTransport) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.JSON_CONTEXT_OPTION, TestJettyJSONContextServer.class.getName());
        startServer(serverTransport, options);

        String channelName = "/foo";
        CountDownLatch latch = new CountDownLatch(1);
        TestService service = new TestService(bayeux, latch);
        service.addService(channelName, "handle");

        TestJettyJSONContextServer jsonContext = (TestJettyJSONContextServer)bayeux.getOption(AbstractServerTransport.JSON_CONTEXT_OPTION);
        jsonContext.getJSON().addConvertor(Holder.class, new HolderConvertor());

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        String value = "bar";
        Request publish = newBayeuxRequest("[{" +
                "\"channel\": \"" + channelName + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {" +
                "    \"class\": \"" + Holder.class.getName() + "\"," +
                "    \"field\":\"" + value + "\"" +
                "}" +
                "}]");
        response = publish.send();
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertNotNull(service.holder);
        Assertions.assertEquals(value, service.holder.field);
    }

    public static class Holder {
        private String field;
    }

    public static class HolderConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object obj, JSON.Output out) {
            Holder holder = (Holder)obj;
            out.addClass(Holder.class);
            out.add("field", holder.field);
        }

        @Override
        public Object fromJSON(Map map) {
            String value = (String)map.get("field");
            Holder holder = new Holder();
            holder.field = value;
            return holder;
        }
    }

    public static class TestService extends AbstractService {
        private final CountDownLatch latch;
        private Holder holder;

        public TestService(BayeuxServer bayeux, CountDownLatch latch) {
            super(bayeux, "test");
            this.latch = latch;
        }

        public void handle(ServerSession remote, ServerMessage message) {
            holder = (Holder)message.getData();
            latch.countDown();
        }
    }

    public static class TestJettyJSONContextServer extends JettyJSONContextServer {
        @Override
        public JSON getJSON() {
            return super.getJSON();
        }
    }
}
