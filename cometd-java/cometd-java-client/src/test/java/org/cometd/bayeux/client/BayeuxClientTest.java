package org.cometd.bayeux.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import bayeux.Extension;
import bayeux.MetaChannelType;
import bayeux.MetaMessage;
import bayeux.MetaMessageListener;
import bayeux.client.Client;
import bayeux.client.Session;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxClientTest
{
    public BayeuxClient newClient() throws Exception
    {
        return null;
    }

    @Test
    public void testHandshakeRequest() throws Exception
    {
        Client client = newClient();

        client.registerExtension(new Extension.Adapter()
        {
            @Override
            public MetaMessage metaIncoming(MetaMessage metaMessage)
            {
                return metaMessage;
            }
        });
    }

    @Test
    public void testHandshakeResponse() throws Exception
    {
        Client client = newClient();

        final CountDownLatch latch = new CountDownLatch(1);
        client.metaChannel(MetaChannelType.HANDSHAKE).subscribe(new MetaMessageListener()
        {
            public void onMetaMessage(MetaMessage metaMessage)
            {
                latch.countDown();
            }
        });

        Session session = client.handshake();
        assertNotNull(session);
        try
        {
            assertTrue(latch.await(1000, TimeUnit.MILLISECONDS));
        }
        finally
        {
            session.disconnect();
        }
    }
}
