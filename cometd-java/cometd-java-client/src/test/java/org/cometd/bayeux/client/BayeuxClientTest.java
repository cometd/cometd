package org.cometd.bayeux.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import bayeux.ChannelSubscription;
import bayeux.Extension;
import bayeux.Message;
import bayeux.MessageListener;
import bayeux.MetaMessage;
import bayeux.MetaMessageListener;
import bayeux.client.Client;
import bayeux.client.Session;
import org.junit.Test;

import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxClientTest
{
    public Client newClient() throws Exception
    {
//        return new OldBayeuxClient();
        return null;
    }

    @Test
    public void testNothing()
    {
    }

//    @Test
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

//    @Test
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

//    @Test
    public void testUsage() throws Exception
    {
        Client client = newClient();

        final Session session = client.handshake();

        final ChannelSubscription subscription = session.channel("/foo").subscribe(new MessageListener()
        {
            public void onMessage(Message message)
            {
                // TODO
            }
        });

        Map<String, Object> data = new HashMap<String, Object>();
        session.channel("/service/foo").publish(data);

        session.batch(new Runnable()
        {
            public void run()
            {
                subscription.unsubscribe();
                session.channel("/one").publish(null);
                session.channel("/two").subscribe(new MessageListener()
                {
                    public void onMessage(Message message)
                    {
                        // TODO
                    }
                });
            }
        });

        session.disconnect();
    }
}
