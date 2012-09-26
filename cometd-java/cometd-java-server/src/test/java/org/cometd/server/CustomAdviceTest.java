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

package org.cometd.server;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JSONContext;
import org.cometd.common.JettyJSONContextClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class CustomAdviceTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testCustomTimeoutViaMessage() throws Exception
    {
        final CountDownLatch connectLatch = new CountDownLatch(2);
        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                connectLatch.countDown();
                return true;
            }
        });

        String channelName = "/connect";
        final long newTimeout = timeout / 2;
        bayeux.createIfAbsent(channelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addListener(new ServerChannel.MessageListener()
                {
                    public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
                    {
                        from.setTimeout(newTimeout);
                        return true;
                    }
                });
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        Future<ContentResponse> futureResponse = connect2.send();
        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        Request publish = newBayeuxRequest("[{" +
                "\"channel\":\"" + channelName + "\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        // Wait for the second connect to return
        response = futureResponse.get(timeout * 2, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.contentAsString());
        Message.Mutable connect = messages[0];
        Map<String,Object> advice = connect.getAdvice();
        Assert.assertNotNull(advice);
        Number timeout = (Number)advice.get("timeout");
        Assert.assertNotNull(timeout);
        Assert.assertEquals(newTimeout, timeout.longValue());
    }

    @Test
    public void testCustomIntervalViaMessage() throws Exception
    {
        final CountDownLatch connectLatch = new CountDownLatch(2);
        bayeux.getChannel(Channel.META_CONNECT).addListener(new ServerChannel.MessageListener()
        {
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
            {
                connectLatch.countDown();
                return true;
            }
        });

        String channelName = "/interval";
        final long newInterval = 1000;
        bayeux.createIfAbsent(channelName, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addListener(new ServerChannel.MessageListener()
                {
                    public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
                    {
                        from.setInterval(newInterval);
                        return true;
                    }
                });
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        Future<ContentResponse> futureResponse = connect2.send();
        Assert.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        Request publish = newBayeuxRequest("[{" +
                "\"channel\":\"" + channelName + "\"," +
                "\"clientId\":\"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        // Wait for the second connect to return
        response = futureResponse.get(timeout * 2, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.contentAsString());
        Message.Mutable connect = messages[0];
        Map<String,Object> advice = connect.getAdvice();
        Assert.assertNotNull(advice);
        Number interval = (Number)advice.get("interval");
        Assert.assertNotNull(interval);
        Assert.assertEquals(newInterval, interval.longValue());
    }

    @Test
    public void testCustomIntervalViaAdvice() throws Exception
    {
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        // The client tells the server that it's going to sleep and won't connect for a while
        // The server must adjust to not expire its session
        long newInterval = 1000;
        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"," +
                "\"advice\":{" +
                "    \"timeout\": 0," +
                "    \"interval\": " + newInterval +
                "}" +
                "}]");
        response = connect2.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        JSONContext.Client jsonContext = new JettyJSONContextClient();
        Message.Mutable[] messages = jsonContext.parse(response.contentAsString());
        Message.Mutable connect = messages[0];
        Map<String,Object> advice = connect.getAdvice();
        Assert.assertNotNull(advice);
        Number interval = (Number)advice.get("interval");
        Assert.assertNotNull(interval);
        Assert.assertEquals(newInterval, interval.longValue());

        // Verify that the server is aware of the interval and will not expire the session
        ServerSessionImpl session = (ServerSessionImpl)bayeux.getSession(clientId);
        long expectedMaxInterval = session.getIntervalTimestamp() - System.currentTimeMillis();
        Assert.assertTrue(expectedMaxInterval > session.getMaxInterval() + newInterval / 2);
    }
}
