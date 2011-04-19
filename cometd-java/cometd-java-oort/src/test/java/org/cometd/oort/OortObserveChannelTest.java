package org.cometd.oort;

import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.client.BayeuxClient;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.server.Server;
import org.junit.Assert;
import org.junit.Test;

public class OortObserveChannelTest extends OortTest
{
    @Test
    public void testObserveChannel() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);
        Server server3 = startServer(0);
        Oort oort3 = startOort(server3);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet13 = oort1.observeComet(oort3.getURL());
        Assert.assertTrue(oortComet13.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Give some time to the connections to establish
        Thread.sleep(1000);

        OortComet oortComet21 = oort2.getComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet31 = oort3.getComet(oort1.getURL());
        Assert.assertTrue(oortComet31.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet23 = oort2.getComet(oort3.getURL());
        Assert.assertTrue(oortComet23.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet32 = oort3.getComet(oort2.getURL());
        Assert.assertTrue(oortComet32.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client1 = startClient(oort1);
        BayeuxClient client2 = startClient(oort2);
        BayeuxClient client3 = startClient(oort3);

        // Oort1 observes the channel, so any publish to Oort2 or Oort3 is forwarded to Oort1
        String channelName = "/oort_test";
        oort1.observeChannel(channelName);

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
        client2.getChannel(channelName).publish(new HashMapMessage());
        Assert.assertTrue(messageLatch1.await(5, TimeUnit.SECONDS));
        Assert.assertFalse(messageLatch3.await(1, TimeUnit.SECONDS));

        // Sending a message to Oort3, must be received by client1 and by client3
        messageLatch1.reset(1);
        client3.getChannel(channelName).publish(new HashMapMessage());
        Assert.assertTrue(messageLatch1.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(messageLatch3.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testObserveWildChannel() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Give some time to the connections to establish
        Thread.sleep(1000);

        OortComet oortComet21 = oort2.getComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client1 = startClient(oort1);
        BayeuxClient client2 = startClient(oort2);

        // Oort1 observes the channel, so any publish to Oort2 or Oort3 is forwarded to Oort1
        String rootChannelName = "/oort_test";
        String wildChannelName = rootChannelName + "/*";
        String channelName = rootChannelName + "/foo";
        oort1.observeChannel(wildChannelName);

        // Subscribe client1
        LatchListener subscribeLatch1 = new LatchListener();
        client1.getChannel(Channel.META_SUBSCRIBE).addListener(subscribeLatch1);
        LatchListener messageLatch1 = new LatchListener(1);
        client1.getChannel(channelName).subscribe(messageLatch1);
        Assert.assertTrue(subscribeLatch1.await(5, TimeUnit.SECONDS));

        // Sending a message to Oort2, must be received by client1
        client2.getChannel(channelName).publish(new HashMapMessage());
        Assert.assertTrue(messageLatch1.await(5, TimeUnit.SECONDS));

        // Be sure it's been received once only
        Thread.sleep(1000);
        Assert.assertEquals(1, messageLatch1.count());
    }

    @Test
    public void testDeobserve() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        BayeuxClient client1 = startClient(oort1);
        BayeuxClient client2 = startClient(oort2);

        // Oort1 observes the channel, so any publish to Oort2 or Oort3 is forwarded to Oort1
        String channelName = "/oort_test";
        oort1.observeChannel(channelName);

        // Subscribe client1
        LatchListener subscribeLatch1 = new LatchListener();
        client1.getChannel(Channel.META_SUBSCRIBE).addListener(subscribeLatch1);
        LatchListener messageLatch1 = new LatchListener(1);
        client1.getChannel(channelName).subscribe(messageLatch1);
        Assert.assertTrue(subscribeLatch1.await(1, TimeUnit.SECONDS));

        // Sending a message to Oort2, must be received by client1
        client2.getChannel(channelName).publish(new HashMapMessage());
        Assert.assertTrue(messageLatch1.await(5, TimeUnit.SECONDS));

        // Deobserve the channel
        oort1.deobserveChannel(channelName);

        // Wait a while to be sure to be unsubscribed
        Thread.sleep(1000);

        // Resend, the message must not be received
        messageLatch1.reset(1);
        client2.getChannel(channelName).publish(new HashMapMessage());
        Assert.assertFalse(messageLatch1.await(1, TimeUnit.SECONDS));
    }
}
