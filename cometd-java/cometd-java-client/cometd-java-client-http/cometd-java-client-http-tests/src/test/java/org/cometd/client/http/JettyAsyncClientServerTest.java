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
package org.cometd.client.http;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.cometd.client.BayeuxClient;
import org.cometd.client.BayeuxClient.State;
import org.cometd.client.http.jetty.JettyAsyncClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.junit.Assert;
import org.junit.Test;

public class JettyAsyncClientServerTest extends ClientServerTest {
    @Test
    public void testMaxMessageSizeExceededViaPublish() throws Exception {
        start(null);

        Map<String, Object> options = new HashMap<>();
        int maxMessageSize = 1024;
        options.put(ClientTransport.MAX_MESSAGE_SIZE_OPTION, maxMessageSize);
        
        ExecutorService threadPool = Executors.newFixedThreadPool(1);
        try {
            BayeuxClient client = new BayeuxClient(cometdURL, new JettyAsyncClientTransport(options, httpClient, threadPool));
    
            client.handshake();
            Assert.assertTrue(client.waitFor(5000, State.CONNECTED));
    
            String channelName = "/max_message_size";
            final CountDownLatch subscribeLatch = new CountDownLatch(1);
            final CountDownLatch messageLatch = new CountDownLatch(1);
            client.getChannel(channelName).subscribe((channel, message) -> messageLatch.countDown(), message -> subscribeLatch.countDown());
            Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));
    
            // Publish a large message that will be echoed back.
            // It will be delivered via the publish, but will not fail
            // because this ClientTransport doesn't care how large it is.
            char[] chars = new char[maxMessageSize];
            Arrays.fill(chars, '0');
            String data = new String(chars);
            final CountDownLatch callbackLatch = new CountDownLatch(1);
            client.getChannel(channelName).publish(data, message -> {
                if (message.isSuccessful()) {
                    callbackLatch.countDown();
                }
            });
    
            Assert.assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
            Assert.assertTrue(messageLatch.await(1, TimeUnit.SECONDS));
    
            disconnectBayeuxClient(client);
        } finally {
            threadPool.shutdown();
        }
    }
}
