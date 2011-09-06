package org.cometd.client;

import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.junit.Assert;
import org.junit.Test;

public class ChannelReleaseTest extends ClientServerTest
{
    @Test
    public void testChannelReleased() throws Exception
    {
        startServer(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(1000, BayeuxClient.State.CONNECTED);

        // Wait for the long poll
        TimeUnit.MILLISECONDS.sleep(500);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.publish("");
        boolean released = channel.release();

        Assert.assertTrue(released);

        ClientSessionChannel newChannel = client.getChannel(channelName);
        Assert.assertNotNull(newChannel);
        Assert.assertNotSame(channel, newChannel);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithListenersNotReleased() throws Exception
    {
        startServer(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(1000, BayeuxClient.State.CONNECTED);

        // Wait for the long poll
        TimeUnit.MILLISECONDS.sleep(500);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.addListener(new ClientSessionChannel.ClientSessionChannelListener()
        {
        });
        channel.publish("");
        boolean released = channel.release();

        Assert.assertFalse(released);

        ClientSessionChannel newChannel = client.getChannel(channelName);
        Assert.assertNotNull(newChannel);
        Assert.assertSame(channel, newChannel);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithSubscriberNotReleased() throws Exception
    {
        startServer(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(1000, BayeuxClient.State.CONNECTED);

        // Wait for the long poll
        TimeUnit.MILLISECONDS.sleep(500);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        channel.subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        });
        channel.publish("");
        boolean released = channel.release();

        Assert.assertFalse(released);

        ClientSessionChannel newChannel = client.getChannel(channelName);
        Assert.assertNotNull(newChannel);
        Assert.assertSame(channel, newChannel);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithListenerRemovedIsReleased() throws Exception
    {
        startServer(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(1000, BayeuxClient.State.CONNECTED);

        // Wait for the long poll
        TimeUnit.MILLISECONDS.sleep(500);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        ClientSessionChannel.ClientSessionChannelListener listener = new ClientSessionChannel.ClientSessionChannelListener()
        {
        };
        channel.addListener(listener);
        channel.publish("");
        boolean released = channel.release();

        Assert.assertFalse(released);

        channel.removeListener(listener);
        Assert.assertTrue(channel.getListeners().isEmpty());
        released = channel.release();

        Assert.assertTrue(released);

        disconnectBayeuxClient(client);
    }

    @Test
    public void testChannelWithSubscriberRemovedIsReleased() throws Exception
    {
        startServer(null);

        BayeuxClient client = newBayeuxClient();
        client.handshake();
        client.waitFor(1000, BayeuxClient.State.CONNECTED);

        // Wait for the long poll
        TimeUnit.MILLISECONDS.sleep(500);

        String channelName = "/foo";
        ClientSessionChannel channel = client.getChannel(channelName);
        ClientSessionChannel.MessageListener listener = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
            }
        };
        channel.subscribe(listener);
        channel.publish("");
        boolean released = channel.release();

        Assert.assertFalse(released);

        channel.unsubscribe(listener);
        Assert.assertTrue(channel.getSubscribers().isEmpty());
        released = channel.release();

        Assert.assertTrue(released);

        disconnectBayeuxClient(client);
    }
}
