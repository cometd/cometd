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
package org.cometd.client;

import org.cometd.bayeux.client.ClientSessionChannel;
import org.junit.Assert;
import org.junit.Test;

public class ClientChannelTest extends ClientServerTest {
    @Test
    public void testSameChannelWithTrailingSlash() throws Exception {
        startServer(null);

        BayeuxClient client = newBayeuxClient();

        String channelName = "/foo";
        ClientSessionChannel channel1 = client.getChannel(channelName);
        ClientSessionChannel channel2 = client.getChannel(channelName + "/");

        Assert.assertSame(channel1, channel2);

        disconnectBayeuxClient(client);
    }
}
