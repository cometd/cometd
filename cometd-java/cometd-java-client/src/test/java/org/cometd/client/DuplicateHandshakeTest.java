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
package org.cometd.client;

import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class DuplicateHandshakeTest extends ClientServerTest {
    @Test
    public void testDuplicateHandshake() throws Exception {
        long timeout = 2000;
        long sweepPeriod = 750;
        Map<String, String> options = new HashMap<>();
        options.put("timeout", String.valueOf(timeout));
        options.put("maxInterval", String.valueOf(timeout));
        options.put("sweepPeriod", String.valueOf(sweepPeriod));
        startServer(options);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        String sessionId = client.getId();

        // Send the second handshake.
        Assert.assertTrue(client.sendHandshake());

        // Wait until the /meta/connect returns, and the maxInterval expires.
        Thread.sleep(timeout * 3 / 2 + sweepPeriod);

        Assert.assertEquals(sessionId, client.getId());
        Assert.assertEquals(1, bayeux.getSessions().size());
        Assert.assertNotNull(bayeux.getSession(sessionId));

        disconnectBayeuxClient(client);
    }
}
