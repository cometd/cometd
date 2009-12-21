package org.cometd.bayeux.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.transport.LocalTransport;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @version $Revision$ $Date$
 */
public class StandardClientBayeuxTest
{
    private StandardClientBayeux newClientBayeux()
    {
        return new StandardClientBayeux(new LocalTransport());
    }

    @Test
    public void testHandshakeCallsHandshakeListener() throws InterruptedException
    {
        ClientBayeux bayeux = newClientBayeux();

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
        assertTrue((Boolean)metaMessage.get(Message.SUCCESSFUL_FIELD));
        // TODO: check message fields

        bayeux.disconnect();
    }
}
