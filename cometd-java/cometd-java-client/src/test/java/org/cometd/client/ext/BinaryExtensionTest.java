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
package org.cometd.client.ext;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.BinaryData;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ClientServerTest;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BinaryExtensionTest extends ClientServerTest {
    @Before
    public void prepare() throws Exception {
        startServer(null);
        bayeux.addExtension(new org.cometd.server.ext.BinaryExtension());
    }

    @Test
    public void testClientBinaryUpload() throws Exception {
        final String channelName = "/binary";

        final byte[] bytes = new byte[1024];
        new Random().nextBytes(bytes);
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);

        final LocalSession service = bayeux.newLocalSession("bin");
        service.addExtension(new BinaryExtension());
        service.handshake();
        service.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                BinaryData data = (BinaryData)message.getData();
                byte[] payload = data.asBytes();
                if (Arrays.equals(payload, bytes)) {
                    Map<String, Object> meta = data.getMetaData();
                    ServerSession remote = bayeux.getSession((String)meta.get("peer"));
                    remote.deliver(service, channelName, new BinaryData(data.asByteBuffer(), data.isLast(), null));
                }
            }
        });

        final CountDownLatch messageLatch = new CountDownLatch(1);
        final BayeuxClient client = newBayeuxClient();
        client.addExtension(new BinaryExtension());
        client.getChannel(channelName).addListener(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                if (!message.isPublishReply()) {
                    BinaryData data = (BinaryData)message.getData();
                    byte[] payload = data.asBytes();
                    if (Arrays.equals(payload, bytes)) {
                        if (data.isLast()) {
                            messageLatch.countDown();
                        }
                    }
                }
            }
        });
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("peer", channel.getSession().getId());
                client.getChannel(channelName).publish(new BinaryData(buffer, true, meta));
            }
        });

        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    @Test
    public void testServerBinaryDownload() throws Exception {
        final String channelName = "/binary";

        final byte[] bytes = new byte[1024];
        new Random().nextBytes(bytes);
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);

        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        final CountDownLatch messageLatch = new CountDownLatch(1);
        final BayeuxClient client = newBayeuxClient();
        client.addExtension(new BinaryExtension());
        client.handshake(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                client.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        BinaryData data = (BinaryData)message.getData();
                        byte[] payload = data.asBytes();
                        if (Arrays.equals(payload, bytes)) {
                            if (data.isLast()) {
                                messageLatch.countDown();
                            }
                        }
                    }
                }, new ClientSessionChannel.MessageListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        subscribeLatch.countDown();
                    }
                });
            }
        });
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));
        bayeux.getChannel(channelName).publish(null, new BinaryData(buffer, true, null));

        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }
}
