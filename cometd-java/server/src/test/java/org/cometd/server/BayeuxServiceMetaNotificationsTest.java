package org.cometd.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Message;
import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.client.HttpClient;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxServiceMetaNotificationsTest extends AbstractBayeuxServiceTest
{
    private Bayeux bayeux;

    protected void customizeBayeux(Bayeux bayeux)
    {
        this.bayeux = bayeux;
    }

    public void testMetaNotifications() throws Exception
    {
        final CountDownLatch handshakeLatch = new CountDownLatch(1);
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch subscribeLatch = new CountDownLatch(1);
        final CountDownLatch unsubscribeLatch = new CountDownLatch(1);
        final CountDownLatch disconnectLatch = new CountDownLatch(1);
        BayeuxService bayeuxService = new BayeuxService(bayeux, "test")
        {
            {
                subscribe(Bayeux.META_HANDSHAKE, "metaHandshake");
                subscribe(Bayeux.META_CONNECT, "metaConnect");
                subscribe(Bayeux.META_SUBSCRIBE, "metaSubscribe");
                subscribe(Bayeux.META_UNSUBSCRIBE, "metaUnsubscribe");
                subscribe(Bayeux.META_DISCONNECT, "metaDisconnect");
            }

            public void metaHandshake(Client remote, Message message)
            {
                handshakeLatch.countDown();
            }

            public void metaConnect(Client remote, Message message)
            {
                connectLatch.countDown();
            }

            public void metaSubscribe(Client remote, Message message)
            {
                subscribeLatch.countDown();
            }

            public void metaUnsubscribe(Client remote, Message message)
            {
                unsubscribeLatch.countDown();
            }

            public void metaDisconnect(Client remote, Message message)
            {
                disconnectLatch.countDown();
            }
        };

        HttpClient httpClient = new HttpClient();
        httpClient.start();
        try
        {
            BayeuxClient bayeuxClient = new BayeuxClient(httpClient, cometdURL);

            // Send the handshake
            bayeuxClient.start();
            assertTrue(handshakeLatch.await(1000, TimeUnit.MILLISECONDS));

            assertTrue(connectLatch.await(1000, TimeUnit.MILLISECONDS));

            String channel = "/foo";
            bayeuxClient.subscribe(channel);
            assertTrue(subscribeLatch.await(1000, TimeUnit.MILLISECONDS));

            bayeuxClient.unsubscribe(channel);
            assertTrue(unsubscribeLatch.await(1000, TimeUnit.MILLISECONDS));

            // Send the disconnect
            bayeuxClient.stop();
            assertTrue(disconnectLatch.await(1000, TimeUnit.MILLISECONDS));
        }
        finally
        {
            httpClient.stop();
        }
    }
}
