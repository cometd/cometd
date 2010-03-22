package org.cometd.server;

import java.util.concurrent.atomic.AtomicBoolean;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Message;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;

/**
 * @version $Revision$ $Date$
 */
public class ConcurrentDisconnectSubscribeTest extends AbstractBayeuxClientServerTest
{
    private Bayeux bayeux;

    protected void customizeBayeux(Bayeux bayeux)
    {
        this.bayeux = bayeux;
    }

    public void testConcurrentDisconnectSubscribe1() throws Exception
    {
        final AtomicBoolean subscribed = new AtomicBoolean(false);
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
            }

            public void metaConnect(Client remote, Message message)
            {
            }

            public void metaSubscribe(Client remote, Message message)
            {
                subscribed.set(true);
            }

            public void metaUnsubscribe(Client remote, Message message)
            {
            }

            public void metaDisconnect(Client remote, Message message)
            {
            }
        };

        ContentExchange handshake = newBayeuxExchange("[{" +
                                                      "\"channel\": \"/meta/handshake\"," +
                                                      "\"version\": \"1.0\"," +
                                                      "\"minimumVersion\": \"1.0\"," +
                                                      "\"supportedConnectionTypes\": [\"long-polling\"]" +
                                                      "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake.getResponseContent());

        ContentExchange connect = newBayeuxExchange("[{" +
                                                    "\"channel\": \"/meta/connect\"," +
                                                    "\"clientId\": \"" + clientId + "\"," +
                                                    "\"connectionType\": \"long-polling\"" +
                                                    "}]");
        httpClient.send(connect);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect.waitForDone());
        assertEquals(200, connect.getResponseStatus());

        // Forge a bad sequence of messages to simulate concurrent arrival of disconnect and subscribe messages
        String channel = "/foo";
        ContentExchange disconnect = newBayeuxExchange("[{" +
                                                       "\"channel\": \"/meta/disconnect\"," +
                                                       "\"clientId\": \"" + clientId + "\"" +
                                                       "},{" +
                                                       "\"channel\": \"/meta/subscribe\"," +
                                                       "\"clientId\": \"" + clientId + "\"," +
                                                       "\"subscription\": \"" + channel + "\"" +
                                                       "}]");
        httpClient.send(disconnect);
        assertEquals(HttpExchange.STATUS_COMPLETED, disconnect.waitForDone());
        assertEquals(200, disconnect.getResponseStatus());
        assertFalse(subscribed.get());
        // The response to the subscribe must be that the client is unknown
        assertTrue(disconnect.getResponseContent().contains("402::"));
    }
}
