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
package org.cometd.server.servlet;

import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class MaxQueuedTest extends AbstractBayeuxClientServerTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testMaxQueued(String serverTransport) throws Exception {
        int maxQueue = 2;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_QUEUE_OPTION, String.valueOf(maxQueue));
        // Makes the test simpler: publishes are only sent via /meta/connect.
        options.put(AbstractServerTransport.META_CONNECT_DELIVERY_OPTION, String.valueOf(true));
        startServer(serverTransport, options);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assertions.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assertions.assertEquals(200, response.getStatus());

        ServerSession serverSession = bayeux.getSession(clientId);
        Assertions.assertNotNull(serverSession);

        serverSession.addListener((ServerSession.QueueMaxedListener)(session, queue, sender, message) -> {
            // Cannot use session.disconnect(), because it will queue the
            // disconnect message and invoke this method again, causing a loop.
            bayeux.removeSession(session);
            return false;
        });

        // Overflow the message queue.
        for (int i = 0; i < maxQueue + 1; ++i) {
            serverSession.deliver(null, "/max_queue", "message_" + i, Promise.noop());
        }

        // Session should be gone.
        Assertions.assertNull(bayeux.getSession(clientId));
    }
}
