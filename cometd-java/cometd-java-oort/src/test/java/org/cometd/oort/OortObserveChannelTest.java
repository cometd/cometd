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
package org.cometd.oort;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.BinaryData;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.client.BayeuxClient;
import org.cometd.server.ext.BinaryExtension;
import org.eclipse.jetty.server.Server;
import org.junit.Assert;
import org.junit.Test;

public class OortObserveChannelTest extends OortTest {
    public OortObserveChannelTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testObserveChannel() throws Exception {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);
        Server server3 = startServer(0);
        Oort oort3 = startOort(server3);

        CountDownLatch latch = new CountDownLatch(6);
        CometJoinedListener listener = new CometJoinedListener(latch);
        oort1.addCometListener(listener);
        oort2.addCometListener(listener);
        oort3.addCometListener(listener);
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet13 = oort1.observeComet(oort3.getURL());
        Assert.assertTrue(oortComet13.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet31 = oort3.findComet(oort1.getURL());
        Assert.assertTrue(oortComet31.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet23 = oort2.findComet(oort3.getURL());
        Assert.assertTrue(oortComet23.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet32 = oort3.findComet(oort2.getURL());
        Assert.assertTrue(oortComet32.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client1 = startClient(oort1, null);
        Assert.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client2 = startClient(oort2, null);
        Assert.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client3 = startClient(oort3, null);
        Assert.assertTrue(client3.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Oort1 observes the channel, so any publish to Oort2 or Oort3 is forwarded to Oort1
        String channelName = "/oort_test";
        oort1.observeChannel(channelName);

        // Wait a while to be sure to be subscribed
        Thread.sleep(1000);

        // Subscribe client1
        LatchListener subscribeLatch1 = new LatchListener();
        client1.getChannel(Channel.META_SUBSCRIBE).addListener(subscribeLatch1);
        LatchListener messageLatch1 = new LatchListener(1);
        client1.getChannel(channelName).subscribe(messageLatch1);
        Assert.assertTrue(subscribeLatch1.await(5, TimeUnit.SECONDS));

        // Subscribe client3
        LatchListener subscribeLatch3 = new LatchListener();
        client3.getChannel(Channel.META_SUBSCRIBE).addListener(subscribeLatch3);
        LatchListener messageLatch3 = new LatchListener(1);
        client3.getChannel(channelName).subscribe(messageLatch3);
        Assert.assertTrue(subscribeLatch3.await(5, TimeUnit.SECONDS));

        // Sending a message to Oort2, must be received by client1 but not by client3
        client2.getChannel(channelName).publish(new HashMap<>());
        Assert.assertTrue(messageLatch1.await(5, TimeUnit.SECONDS));
        Assert.assertFalse(messageLatch3.await(1, TimeUnit.SECONDS));

        // Sending a message to Oort3, must be received by client1 and by client3
        messageLatch1.reset(1);
        client3.getChannel(channelName).publish(new HashMap<>());
        Assert.assertTrue(messageLatch1.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(messageLatch3.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testObserveWildChannel() throws Exception {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(2);
        CometJoinedListener listener = new CometJoinedListener(latch);
        oort1.addCometListener(listener);
        oort2.addCometListener(listener);
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client1 = startClient(oort1, null);
        Assert.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client2 = startClient(oort2, null);
        Assert.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Oort1 observes the channel, so any publish to Oort2 is forwarded to Oort1
        String rootChannelName = "/oort_test";
        String wildChannelName = rootChannelName + "/*";
        String channelName = rootChannelName + "/foo";
        oort1.observeChannel(wildChannelName);

        // Wait a while to be sure to be subscribed
        Thread.sleep(1000);

        // Subscribe client1
        LatchListener subscribeLatch1 = new LatchListener();
        client1.getChannel(Channel.META_SUBSCRIBE).addListener(subscribeLatch1);
        LatchListener messageLatch1 = new LatchListener(1);
        client1.getChannel(channelName).subscribe(messageLatch1);
        Assert.assertTrue(subscribeLatch1.await(5, TimeUnit.SECONDS));

        // Sending a message to Oort2, must be received by client1
        client2.getChannel(channelName).publish(new HashMap<>());
        Assert.assertTrue(messageLatch1.await(5, TimeUnit.SECONDS));

        // Be sure it's been received once only
        Thread.sleep(1000);
        Assert.assertEquals(1, messageLatch1.count());
    }

    @Test
    public void testDeobserve() throws Exception {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(2);
        CometJoinedListener listener = new CometJoinedListener(latch);
        oort1.addCometListener(listener);
        oort2.addCometListener(listener);
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client1 = startClient(oort1, null);
        Assert.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client2 = startClient(oort2, null);
        Assert.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Oort1 observes the channel, so any publish to Oort2 is forwarded to Oort1
        String channelName = "/oort_test";
        oort1.observeChannel(channelName);

        // Wait a while to be sure to be subscribed
        Thread.sleep(1000);

        // Subscribe client1
        LatchListener subscribeLatch1 = new LatchListener();
        client1.getChannel(Channel.META_SUBSCRIBE).addListener(subscribeLatch1);
        LatchListener messageLatch1 = new LatchListener(1);
        client1.getChannel(channelName).subscribe(messageLatch1);
        Assert.assertTrue(subscribeLatch1.await(5, TimeUnit.SECONDS));

        // Sending a message to Oort2, must be received by client1
        client2.getChannel(channelName).publish(new HashMap<>());
        Assert.assertTrue(messageLatch1.await(5, TimeUnit.SECONDS));

        // Deobserve the channel
        oort1.deobserveChannel(channelName);

        // Wait a while to be sure to be unsubscribed
        Thread.sleep(1000);

        // Resend, the message must not be received
        messageLatch1.reset(1);
        client2.getChannel(channelName).publish(new HashMap());
        Assert.assertFalse(messageLatch1.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testBinaryMessageOnObservedChannel() throws Exception {
        Server server1 = startServer(0);
        BayeuxServer bayeux1 = (BayeuxServer)server1.getAttribute(BayeuxServer.ATTRIBUTE);
        bayeux1.addExtension(new BinaryExtension());
        Oort oort1 = startOort(server1);
        oort1.getOortSession().addExtension(new org.cometd.client.ext.BinaryExtension());
        oort1.setBinaryExtensionEnabled(true);
        Server server2 = startServer(0);
        BayeuxServer bayeux2 = (BayeuxServer)server2.getAttribute(BayeuxServer.ATTRIBUTE);
        bayeux2.addExtension(new BinaryExtension());
        Oort oort2 = startOort(server2);
        oort2.getOortSession().addExtension(new org.cometd.client.ext.BinaryExtension());
        oort2.setBinaryExtensionEnabled(true);

        CountDownLatch latch = new CountDownLatch(2);
        CometJoinedListener listener = new CometJoinedListener(latch);
        oort1.addCometListener(listener);
        oort2.addCometListener(listener);
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client1 = startClient(oort1, null);
        client1.addExtension(new org.cometd.client.ext.BinaryExtension());
        Assert.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient client2 = startClient(oort2, null);
        client2.addExtension(new org.cometd.client.ext.BinaryExtension());
        Assert.assertTrue(client2.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Oort1 observes the channel, so any publish to Oort2 is forwarded to Oort1.
        String channelName = "/oort_binary";
        oort1.observeChannel(channelName);

        // Wait a while to be sure to be subscribed.
        Thread.sleep(1000);

        final byte[] bytes = new byte[1024];
        new Random().nextBytes(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        // Subscribe client1.
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        final CountDownLatch messageLatch = new CountDownLatch(1);
        client1.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                BinaryData data = (BinaryData)message.getData();
                byte[] payload = data.asBytes();
                if (Arrays.equals(payload, bytes))
                    messageLatch.countDown();
            }
        }, new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                subscribeLatch.countDown();
            }
        });
        Assert.assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS));

        // Sending a message to Oort2, must be received by client1.
        final CountDownLatch publishLatch = new CountDownLatch(1);
        client2.getChannel(channelName).publish(new BinaryData(buffer, true, null), new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                publishLatch.countDown();
            }
        });

        Assert.assertTrue(publishLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }
}
