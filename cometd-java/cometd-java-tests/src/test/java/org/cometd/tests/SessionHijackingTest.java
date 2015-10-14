/*
 * Copyright (c) 2008-2015 the original author or authors.
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
package org.cometd.tests;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.client.BayeuxClient;
import org.junit.Assert;
import org.junit.Test;

public class SessionHijackingTest extends AbstractClientServerTest
{
    public SessionHijackingTest(Transport transport)
    {
        super(transport);
    }

    @Test
    public void testSessionHijacking() throws Exception
    {
        startServer(serverOptions());

        BayeuxClient client1 = newBayeuxClient();
        client1.handshake();
        Assert.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));

        final BayeuxClient client2 = newBayeuxClient();
        client2.handshake();
        Assert.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Client1 tries to impersonate Client2.
        client1.addExtension(new ClientSession.Extension.Adapter()
        {
            @Override
            public boolean send(ClientSession session, Message.Mutable message)
            {
                message.setClientId(client2.getId());
                return true;
            }
        });
        String channel = "/session_mismatch";
        client1.getChannel(channel).publish("data");

        // Expect Client1 to be disconnected.
        Assert.assertTrue(client1.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // Client2 should be connected.
        Assert.assertTrue(client2.waitFor(1000, BayeuxClient.State.CONNECTED));

        disconnectBayeuxClient(client2);
    }
}
