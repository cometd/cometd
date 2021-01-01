/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import java.util.Queue;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class MaxQueuedTest extends AbstractBayeuxClientServerTest {
    public MaxQueuedTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testMaxQueued() throws Exception {
        int maxQueue = 2;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_QUEUE_OPTION, String.valueOf(maxQueue));
        // Makes the test simpler: publishes are only sent via /meta/connect.
        options.put(AbstractServerTransport.META_CONNECT_DELIVERY_OPTION, String.valueOf(true));
        startServer(options);

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        String clientId = extractClientId(response);

        Request connect1 = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect1.send();
        Assert.assertEquals(200, response.getStatus());

        ServerSession serverSession = bayeux.getSession(clientId);
        Assert.assertNotNull(serverSession);

        serverSession.addListener(new ServerSession.MaxQueueListener() {
            @Override
            public boolean queueMaxed(ServerSession session, Queue<ServerMessage> queue, ServerSession sender, Message message) {
                // Cannot use session.disconnect(), because it will queue the
                // disconnect message and invoke this method again, causing a loop.
                bayeux.removeSession(session);
                return false;
            }
        });

        // Overflow the message queue.
        for (int i = 0; i < maxQueue + 1; ++i) {
            serverSession.deliver(null, "/max_queue", "message_" + i);
        }

        // Session should be gone.
        Assert.assertNull(bayeux.getSession(clientId));
    }
}
