/*
 * Copyright (c) 2008-2018 the original author or authors.
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
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JettyJSONContextClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DisconnectTest extends AbstractBayeuxClientServerTest {
    public DisconnectTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception {
        startServer(null);
    }

    @Test
    public void testDisconnect() throws Exception {
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send();
        Assert.assertEquals(200, response.getStatus());

        ServerSession serverSession = bayeux.getSession(clientId);
        Assert.assertNotNull(serverSession);

        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");

        FutureResponseListener listener = new FutureResponseListener(connect2);
        connect2.send(listener);

        // Wait for the /meta/connect to be suspended
        Thread.sleep(1000);

        final CountDownLatch latch = new CountDownLatch(1);
        serverSession.addListener(new ServerSession.RemoveListener() {
            @Override
            public void removed(ServerSession session, boolean timeout) {
                latch.countDown();
            }
        });

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assert.assertEquals(200, response.getStatus());

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());
        Message.Mutable connectReply = new JettyJSONContextClient().parse(response.getContentAsString())[0];
        Assert.assertEquals(Channel.META_CONNECT, connectReply.getChannel());
        Map<String, Object> advice = connectReply.getAdvice(false);
        Assert.assertTrue(Message.RECONNECT_NONE_VALUE.equals(advice.get(Message.RECONNECT_FIELD)));
    }
}
