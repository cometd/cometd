package org.cometd.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxServiceMetaNotificationsTest extends AbstractBayeuxClientServerTest
{
    private BayeuxServerImpl bayeux;

    protected void customizeBayeux(BayeuxServerImpl bayeux)
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
        new AbstractService(bayeux, "test")
        {
            {
                addService(Channel.META_HANDSHAKE, "metaHandshake");
                addService(Channel.META_CONNECT, "metaConnect");
                addService(Channel.META_SUBSCRIBE, "metaSubscribe");
                addService(Channel.META_UNSUBSCRIBE, "metaUnsubscribe");
                addService(Channel.META_DISCONNECT, "metaDisconnect");
            }

            public void metaHandshake(ServerSession remote, Message message)
            {
                handshakeLatch.countDown();
            }

            public void metaConnect(ServerSession remote, Message message)
            {
                connectLatch.countDown();
            }

            public void metaSubscribe(ServerSession remote, Message message)
            {
                subscribeLatch.countDown();
            }

            public void metaUnsubscribe(ServerSession remote, Message message)
            {
                unsubscribeLatch.countDown();
            }

            public void metaDisconnect(ServerSession remote, Message message)
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

        String clientId = extractClientId(handshake);
        String bayeuxCookie = extractBayeuxCookie(handshake);

        ContentExchange connect = newBayeuxExchange("[{" +
                                                 "\"channel\": \"/meta/connect\"," +
                                                 "\"clientId\": \"" + clientId + "\"," +
                                                 "\"connectionType\": \"long-polling\"" +
                                                 "}]");
        connect.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
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
