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

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ConcurrentHandshakeFailureSubscribePublishTest extends AbstractBayeuxClientServerTest {
    public ConcurrentHandshakeFailureSubscribePublishTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception {
        startServer(null);
    }

    @Test
    public void testConcurrentHandshakeFailureAndSubscribe() throws Exception {
        // A bad sequence of messages results in the server rejecting them.
        String channelName = "/foo";
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}, {" +
                "\"clientId\": null," +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"" + channelName + "\"" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(500, response.getStatus());
    }

    @Test
    public void testConcurrentHandshakeFailureAndPublish() throws Exception {
        // A bad sequence of messages results in the server rejecting them.
        String channelName = "/foo";
        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}, {" +
                "\"clientId\": null," +
                "\"channel\": \"" + channelName + "\"," +
                "\"data\": {}" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(500, response.getStatus());
    }
}
