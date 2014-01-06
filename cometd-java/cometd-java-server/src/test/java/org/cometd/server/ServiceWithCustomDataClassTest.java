/*
 * Copyright (c) 2008-2014 the original author or authors.
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
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.Assert;
import org.junit.Test;

public class ServiceWithCustomDataClassTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testServiceWithCustomDataClass() throws Exception
    {
        Map<String, String> options = new HashMap<String, String>();
        options.put(BayeuxServerImpl.JSON_CONTEXT, TestJettyJSONContextServer.class.getName());
        startServer(options);

        String channelName = "/foo";
        CountDownLatch latch = new CountDownLatch(1);
        TestService service = new TestService(bayeux, latch);
        service.addService(channelName, "handle");

        TestJettyJSONContextServer jsonContext = (TestJettyJSONContextServer)bayeux.getOption(BayeuxServerImpl.JSON_CONTEXT);
        jsonContext.getJSON().addConvertor(Holder.class, new HolderConvertor());

        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        Assert.assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);

        String value = "bar";
        ContentExchange publish = newBayeuxExchange("[{" +
                "\"channel\": \"" + channelName + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {" +
                "    \"class\": \"" + Holder.class.getName() + "\"," +
                "    \"field\":\"" + value + "\"" +
                "}" +
                "}]");
        httpClient.send(publish);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        Assert.assertEquals(200, publish.getResponseStatus());

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertNotNull(service.holder);
        Assert.assertEquals(value, service.holder.field);
    }

    public static class Holder
    {
        private String field;
    }

    public static class HolderConvertor implements JSON.Convertor
    {
        public void toJSON(Object obj, JSON.Output out)
        {
            Holder holder = (Holder)obj;
            out.addClass(Holder.class);
            out.add("field", holder.field);
        }

        public Object fromJSON(Map map)
        {
            String value = (String)map.get("field");
            Holder holder = new Holder();
            holder.field = value;
            return holder;
        }
    }

    public static class TestService extends AbstractService
    {
        private final CountDownLatch latch;
        private Holder holder;

        public TestService(BayeuxServer bayeux, CountDownLatch latch)
        {
            super(bayeux, "test");
            this.latch = latch;
        }

        public void handle(ServerSession remote, Holder data)
        {
            holder = data;
            latch.countDown();
        }
    }

    public static class TestJettyJSONContextServer extends JettyJSONContextServer
    {
        @Override
        public JSON getJSON()
        {
            return super.getJSON();
        }
    }
}
