/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class ServerRedeployTest extends AbstractBayeuxClientServerTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testServerRedeploy(String serverTransport) throws Exception {
        startServer(serverTransport, null);

        Request handshake = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);
        ServerSession session = bayeux.getSession(clientId);

        CountDownLatch connectLatch = new CountDownLatch(1);
        session.addListener(new ServerSession.HeartBeatListener() {
            @Override
            public void onSuspended(ServerSession session, ServerMessage message, long timeout) {
                connectLatch.countDown();
            }
        });

        Request connect = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"," +
                "\"advice\": { \"timeout\": 0 }" +
                "}]");
        response = connect.send();
        Assertions.assertEquals(200, response.getStatus());

        connect = newBayeuxRequest("" +
                "[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        FutureResponseListener futureResponse = new FutureResponseListener(connect);
        connect.send(futureResponse);

        Assertions.assertTrue(connectLatch.await(5, TimeUnit.SECONDS));

        // Stop the context; this is the first half of a redeploy
        context.stop();

        // Expect the connect to be back with an exception
        response = futureResponse.get(timeout * 2, TimeUnit.SECONDS);
        Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
    }
}
