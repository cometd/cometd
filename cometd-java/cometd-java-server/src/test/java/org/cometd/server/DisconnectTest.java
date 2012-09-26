/*
 * Copyright (c) 2010 the original author or authors.
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

import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class DisconnectTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testDisconnect() throws Exception
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

        Request connect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        response = connect.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        ServerSession serverSession = bayeux.getSession(clientId);
        Assert.assertNotNull(serverSession);

        final CountDownLatch latch = new CountDownLatch(1);
        serverSession.addListener(new ServerSession.RemoveListener()
        {
            public void removed(ServerSession session, boolean timeout)
            {
                latch.countDown();
            }
        });

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
