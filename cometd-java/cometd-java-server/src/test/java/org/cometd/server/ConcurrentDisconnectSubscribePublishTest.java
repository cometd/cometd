package org.cometd.server;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;

/**
 * @version $Revision$ $Date$
 */
public class ConcurrentDisconnectSubscribePublishTest extends AbstractBayeuxClientServerTest
{
    private BayeuxServer bayeux;

    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        this.bayeux = bayeux;
    }

    public void testDisconnectSubscribe() throws Exception
    {
        final AtomicBoolean subscribed = new AtomicBoolean(false);
        bayeux.addListener(new BayeuxServer.SubscriptionListener()
        {
            public void unsubscribed(ServerSession session, ServerChannel channel)
            {
            }

            public void subscribed(ServerSession session, ServerChannel channel)
            {
                subscribed.set(true);
            }
        });

        final AtomicBoolean serviced = new AtomicBoolean(false);
        new AbstractService(bayeux, "test")
        {
            {
                addService(Channel.META_SUBSCRIBE, "metaSubscribe");
            }

            public void metaSubscribe(ServerSession remote, Message message)
            {
                serviced.set(remote!=null && remote.isHandshook());
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

        String clientId = extractClientId(handshake);
        String bayeuxCookie = extractBayeuxCookie(handshake);

        ContentExchange connect = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
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

        assertFalse("not subscribed", subscribed.get());
        assertFalse("not serviced", serviced.get());
        // The response to the subscribe must be that the client is unknown
        assertTrue(disconnect.getResponseContent().contains("402::"));
    }

    public void testDisconnectPublish() throws Exception
    {
        final String channel = "/foo";
        final AtomicInteger publishes = new AtomicInteger();
        new AbstractService(bayeux, "test")
        {
            {
                addService(channel, "handle");
            }

            public void handle(ServerSession remote, Message message)
            {
                publishes.incrementAndGet();
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

        String clientId = extractClientId(handshake);
        String bayeuxCookie = extractBayeuxCookie(handshake);

        ContentExchange connect = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect.waitForDone());
        assertEquals(200, connect.getResponseStatus());

        ContentExchange subscribe = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/subscribe\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"subscription\": \"" + channel + "\"" +
                "}]");
        httpClient.send(subscribe);
        assertEquals(HttpExchange.STATUS_COMPLETED, subscribe.waitForDone());
        assertEquals(200, subscribe.getResponseStatus());

        // Forge a bad sequence of messages to simulate concurrent arrival of disconnect and publish messages
        ContentExchange disconnect = newBayeuxExchange("[{" +
                "\"channel\": \"" + channel + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "},{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "},{" +
                "\"channel\": \"" + channel + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(disconnect);
        assertEquals(HttpExchange.STATUS_COMPLETED, disconnect.waitForDone());
        assertEquals(200, disconnect.getResponseStatus());
        assertEquals(1, publishes.get());
        // The response to the subscribe must be that the client is unknown
        assertTrue(disconnect.getResponseContent().contains("402::"));
    }
}
