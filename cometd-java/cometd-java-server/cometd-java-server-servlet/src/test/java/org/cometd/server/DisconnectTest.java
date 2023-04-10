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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.JettyJSONContextClient;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FutureResponseListener;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class DisconnectTest extends AbstractBayeuxClientServerTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testDisconnect(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send();
        Assertions.assertEquals(200, response.getStatus());

        ServerSession serverSession = bayeux.getSession(clientId);
        Assertions.assertNotNull(serverSession);

        Request connect2 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");

        FutureResponseListener listener = new FutureResponseListener(connect2);
        connect2.send(listener);

        // Wait for the /meta/connect to be suspended
        Thread.sleep(1000);

        CountDownLatch latch = new CountDownLatch(1);
        serverSession.addListener((ServerSession.RemovedListener)(s, m, t) -> latch.countDown());

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send();
        Assertions.assertEquals(200, response.getStatus());

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        response = listener.get(5, TimeUnit.SECONDS);
        Assertions.assertEquals(200, response.getStatus());
        Message.Mutable connectReply = new JettyJSONContextClient().parse(response.getContentAsString())[0];
        Assertions.assertEquals(Channel.META_CONNECT, connectReply.getChannel());
        Map<String, Object> advice = connectReply.getAdvice(false);
        Assertions.assertEquals(Message.RECONNECT_NONE_VALUE, advice.get(Message.RECONNECT_FIELD));
    }
}
