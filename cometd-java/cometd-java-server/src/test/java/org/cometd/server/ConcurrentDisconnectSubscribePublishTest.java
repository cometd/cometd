package org.cometd.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
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

    public void testConcurrentDisconnectPublish() throws Exception
    {
        final String channel = "/foo";
        final CountDownLatch publishLatch = new CountDownLatch(1);
        final CountDownLatch disconnectLatch = new CountDownLatch(1);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean publishWait = new AtomicBoolean(false);
        final AtomicBoolean latchWait = new AtomicBoolean(false);

        bayeux.addExtension(new BayeuxServer.Extension()
        {
            public boolean rcv(ServerSession from, ServerMessage.Mutable message)
            {
                return true;
            }

            public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message)
            {
                return true;
            }

            public boolean sendMeta(ServerSession to, ServerMessage.Mutable message)
            {
                return true;
            }

            public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message)
            {
                if (channel.equals(message.getChannel()))
                {
                    // Pretty obscure code that simulates concurrency
                    // We want to simulate the condition when a
                    // disconnected or expired client is asked to deliver
                    // a message; the client must detect that is
                    // disconnected and unsubscribe itself from the channel

                    // Step 1: a deliver is being executed for the client
                    try
                    {
                        // Step 2: add a listener to be notified when the client is removed
                        bayeux.addListener(new BayeuxServer.SessionListener()
                        {
                            public void sessionAdded(ServerSession session)
                            {
                            }

                            public void sessionRemoved(ServerSession session, boolean timedout)
                            {
                                try
                                {
                                    // Step 6: disconnect arrived on server, signal the publish to continue
                                    publishLatch.countDown();
                                    // Step 7: wait to allow the publish to complete before the disconnect
                                    latchWait.set(latch.await(1000, TimeUnit.MILLISECONDS));
                                }
                                catch (InterruptedException x)
                                {
                                    // Ignored
                                }
                            }
                        });

                        // Step 3: add a listener to be notified when the client is unsubscribed from the channel
                        bayeux.getChannel(channel).addListener(new ServerChannel.SubscriptionListener()
                        {
                            public void subscribed(ServerSession session, ServerChannel channel)
                            {
                            }

                            public void unsubscribed(ServerSession session, ServerChannel channel)
                            {
                                // Step 8: the client detects that is disconnected and unsubscribes itself from the channel
                                latch.countDown();
                            }
                        });

                        // Step 4: signal the test code to issue a disconnect now
                        disconnectLatch.countDown();
                        // Step 5: wait for the disconnect to arrive to the server
                        publishWait.set(publishLatch.await(1000, TimeUnit.MILLISECONDS));
                    }
                    catch (InterruptedException x)
                    {
                        // Ignored
                    }
                }
                return true;
            }
        });

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

        // Publish
        ContentExchange publish = newBayeuxExchange("[{" +
                "\"channel\": \"" + channel + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish);
        assertTrue(disconnectLatch.await(1000, TimeUnit.MILLISECONDS));
        // Now we're waiting on the server to simulate the concurrency of a disconnect message
        ContentExchange disconnect = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        httpClient.send(disconnect);

        assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        assertEquals(200, publish.getResponseStatus());
        assertTrue(publishWait.get());

        assertEquals(HttpExchange.STATUS_COMPLETED, disconnect.waitForDone());
        assertEquals(200, disconnect.getResponseStatus());
        assertTrue(latchWait.get());
    }
}
