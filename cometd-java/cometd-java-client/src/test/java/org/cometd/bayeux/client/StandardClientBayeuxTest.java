package org.cometd.bayeux.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Extension;
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
        assertEquals(MetaChannelType.HANDSHAKE, metaMessage.getMetaChannel().getType());
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
                if (metaMessage.getMetaChannel().getType() == MetaChannelType.CONNECT)
                {
                    latch.countDown();
                }
                return metaMessage;
            }
        });
        bayeux.handshake();
        Assert.assertTrue(latch.await(1, TimeUnit.SECONDS));
    }
}
