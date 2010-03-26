package org.cometd.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Message;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxServiceMetaNotificationsTest extends AbstractBayeuxClientServerTest
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

        ContentExchange handshake = newBayeuxExchange("[{" +
                                                  "\"channel\": \"/meta/handshake\"," +
                                                  "\"version\": \"1.0\"," +
                                                  "\"minimumVersion\": \"1.0\"," +
                                                  "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                                  "}]");
        httpClient.send(handshake);
        assertTrue(handshakeLatch.await(1000, TimeUnit.MILLISECONDS));
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake.getResponseContent());

        ContentExchange connect = newBayeuxExchange("[{" +
                                                 "\"channel\": \"/meta/connect\"," +
                                                 "\"clientId\": \"" + clientId + "\"," +
                                                 "\"connectionType\": \"long-polling\"" +
                                                 "}]");
        httpClient.send(connect);
        assertTrue(connectLatch.await(1000, TimeUnit.MILLISECONDS));
        assertEquals(HttpExchange.STATUS_COMPLETED, connect.waitForDone());
        assertEquals(200, connect.getResponseStatus());

        String channel = "/foo";
        ContentExchange subscribe = newBayeuxExchange("[{" +
                                                   "\"channel\": \"/meta/subscribe\"," +
                                                   "\"clientId\": \"" + clientId + "\"," +
                                                   "\"subscription\": \"" + channel + "\"" +
                                                   "}]");
        httpClient.send(subscribe);
        assertTrue(subscribeLatch.await(1000, TimeUnit.MILLISECONDS));
        assertEquals(HttpExchange.STATUS_COMPLETED, subscribe.waitForDone());
        assertEquals(200, subscribe.getResponseStatus());

        ContentExchange unsubscribe = newBayeuxExchange("[{" +
                                                     "\"channel\": \"/meta/unsubscribe\"," +
                                                     "\"clientId\": \"" + clientId + "\"," +
                                                     "\"subscription\": \"" + channel + "\"" +
                                                     "}]");
        httpClient.send(unsubscribe);
        assertTrue(unsubscribeLatch.await(1000, TimeUnit.MILLISECONDS));
        assertEquals(HttpExchange.STATUS_COMPLETED, unsubscribe.waitForDone());
        assertEquals(200, unsubscribe.getResponseStatus());

        ContentExchange disconnect = newBayeuxExchange("[{" +
                                                    "\"channel\": \"/meta/disconnect\"," +
                                                    "\"clientId\": \"" + clientId + "\"" +
                                                    "}]");
        httpClient.send(disconnect);
        assertTrue(disconnectLatch.await(1000, TimeUnit.MILLISECONDS));
        assertEquals(HttpExchange.STATUS_COMPLETED, disconnect.waitForDone());
        assertEquals(200, disconnect.getResponseStatus());
    }
}
