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
package org.cometd.client.http;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.JacksonJSONContextClient;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.AbstractService;
import org.cometd.server.JacksonJSONContextServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JacksonContextTest extends ClientServerTest {
    @Test
    public void testAllMessagesUseJackson() throws Exception {
        Map<String, String> serverParams = new HashMap<>();
        serverParams.put(AbstractServerTransport.JSON_CONTEXT_OPTION, JacksonJSONContextServer.class.getName());
        start(serverParams);

        Map<String, Object> clientParams = new HashMap<>();
        clientParams.put(ClientTransport.JSON_CONTEXT_OPTION, JacksonJSONContextClient.class.getName());
        BayeuxClient client = new BayeuxClient(cometdURL, new JettyHttpClientTransport(clientParams, httpClient));

        client.handshake();
        Assertions.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the long poll
        Thread.sleep(1000);

        String channelName = "/test_jackson";
        CountDownLatch localLatch = new CountDownLatch(2);
        new JacksonService(bayeux, channelName, localLatch);

        ClientSessionChannel channel = client.getChannel(channelName);
        CountDownLatch clientLatch = new CountDownLatch(3);
        client.batch(() -> {
            channel.subscribe(new ClientSessionChannel.MessageListener() {
                private boolean republishSeen;
                private boolean deliverSeen;

                @Override
                public void onMessage(ClientSessionChannel channel1, Message message) {
                    Map<String, Object> data = message.getDataAsMap();
                    Assertions.assertTrue(data.containsKey("publish"));
                    republishSeen |= data.containsKey("republish");
                    deliverSeen |= data.containsKey("deliver");
                    if (clientLatch.getCount() == 1 && !republishSeen && !deliverSeen) {
                        Assertions.fail();
                    }
                    clientLatch.countDown();
                }
            });
            Map<String, Object> data = new HashMap<>();
            data.put("publish", true);
            channel.publish(data);
        });

        Assertions.assertTrue(localLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    public static class JacksonService extends AbstractService {
        private final String channelName;

        public JacksonService(BayeuxServer bayeux, String channelName, CountDownLatch localLatch) {
            super(bayeux, channelName);
            this.channelName = channelName;
            addService(channelName, "process");

            getLocalSession().getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener() {
                private boolean republishSeen;

                @Override
                public void onMessage(ClientSessionChannel channel, Message message) {
                    Map<String, Object> data = message.getDataAsMap();
                    Assertions.assertTrue(data.containsKey("publish"));
                    republishSeen |= data.containsKey("republish");
                    if (localLatch.getCount() == 1 && !republishSeen) {
                        Assertions.fail();
                    }
                    localLatch.countDown();
                }
            });
        }

        @SuppressWarnings("unused")
        public void process(ServerSession session, ServerMessage message) {
            // Republish
            Map<String, Object> data = message.getDataAsMap();
            Map<String, Object> republishData = new HashMap<>(data);
            republishData.put("republish", true);
            getBayeux().getChannel(channelName).publish(getServerSession(), republishData, Promise.noop());
            // Deliver
            Map<String, Object> deliverData = new HashMap<>(data);
            deliverData.put("deliver", true);
            session.deliver(getServerSession(), channelName, deliverData, Promise.noop());
        }
    }
}
