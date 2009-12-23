package org.cometd.bayeux.client;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Extension;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.MetaChannelType;
import org.cometd.bayeux.MetaMessage;
import org.cometd.bayeux.client.transport.LocalTransport;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @version $Revision$ $Date$
 */
public class StandardClientBayeuxTest
{
    private LocalTransport transport;
    private ClientBayeux bayeux;

    @Before
    public void init()
    {
        transport = new LocalTransport();
        bayeux = new StandardClientBayeux(transport);
    }

    @After
    public void destroy()
    {
        bayeux.disconnect();
    }

/*
    @Test
    public void testHandshakeTimeout() throws Exception
    {
        // Handshake sent, but no reply from the server
        // The client must timeout and notify listeners

        bayeux.setMaxNetworkDelay(1000);
        bayeux.handshake();
    }
*/

    @Test
    public void testHandshakeCallsHandshakeListener() throws Exception
    {
        final AtomicReference<MetaMessage> metaMessageRef = new AtomicReference<MetaMessage>();
        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.getMetaChannel(MetaChannelType.HANDSHAKE).subscribe(new MetaMessageListener()
        {
            public void onMetaMessage(MetaMessage metaMessage)
            {
                metaMessageRef.set(metaMessage);
                latch.countDown();
            }
        });
        bayeux.handshake();
        Assert.assertTrue(latch.await(1, TimeUnit.SECONDS));
        MetaMessage metaMessage = metaMessageRef.get();
        assertNotNull(metaMessage);
        assertEquals(MetaChannelType.HANDSHAKE.getName(), metaMessage.getChannelName());
        assertTrue(metaMessage.isSuccessful());

        bayeux.disconnect();
    }

    @Test
    public void testConnectAfterHandshake() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.addExtension(new Extension.Adapter()
        {
            @Override
            public MetaMessage.Mutable metaOutgoing(MetaMessage.Mutable metaMessage)
            {
                if (MetaChannelType.CONNECT.getName().equals(metaMessage.getChannelName()))
                {
                    latch.countDown();
                }
                return metaMessage;
            }
        });
        bayeux.handshake();
        Assert.assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testPublish() throws Exception
    {
        final String channelName = "/test";

        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.addExtension(new Extension.Adapter()
        {
            @Override
            public MetaMessage.Mutable metaIncoming(MetaMessage.Mutable metaMessage)
            {
                if (MetaChannelType.HANDSHAKE.getName().equals(metaMessage.getChannelName()))
                {
                    handshakeLatch.countDown();
                }
                return metaMessage;
            }

            @Override
            public Message.Mutable outgoing(Message.Mutable message)
            {
                if (channelName.equals(message.getChannelName()))
                {
                    latch.countDown();
                }
                return message;
            }
        });
        bayeux.handshake();
        assertTrue(handshakeLatch.await(1, TimeUnit.SECONDS));

        bayeux.getChannel(channelName).publish(new HashMap<String, Object>());
        assertTrue(latch.await(1, TimeUnit.SECONDS));
    }
}
