package org.cometd.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.Bayeux;
import org.cometd.Channel;
import org.cometd.Client;
import org.cometd.ClientBayeuxListener;
import org.cometd.Extension;
import org.cometd.Message;
import org.cometd.SubscriptionListener;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;

/**
 * @version $Revision$ $Date$
 */
public class ConcurrentDisconnectSubscribePublishTest extends AbstractBayeuxClientServerTest
{
    private Bayeux bayeux;

    protected void customizeBayeux(Bayeux bayeux)
    {
        this.bayeux = bayeux;
    }

    public void testDisconnectSubscribe() throws Exception
    {
        final AtomicBoolean subscribed = new AtomicBoolean(false);
        BayeuxService bayeuxService = new BayeuxService(bayeux, "test")
        {
            {
                subscribe(Bayeux.META_SUBSCRIBE, "metaSubscribe");
            }

            public void metaSubscribe(Client remote, Message message)
            {
                subscribed.set(true);
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

    public void testDisconnectPublish() throws Exception
    {
        final String channel = "/foo";
        final AtomicInteger publishes = new AtomicInteger();
        BayeuxService bayeuxService = new BayeuxService(bayeux, "test")
        {
            {
                subscribe(channel, "handle");
            }

            public void handle(Client remote, Message message)
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

        String clientId = extractClientId(handshake.getResponseContent());

        ContentExchange connect = newBayeuxExchange("[{" +
                                                    "\"channel\": \"/meta/connect\"," +
                                                    "\"clientId\": \"" + clientId + "\"," +
                                                    "\"connectionType\": \"long-polling\"" +
                                                    "}]");
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

        bayeux.addExtension(new Extension()
        {
            public Message rcv(Client client, Message message)
            {
                return message;
            }

            public Message rcvMeta(Client client, Message message)
            {
                return message;
            }

            public Message send(Client client, Message message)
            {
                return message;
            }

            public Message sendMeta(Client client, Message message)
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
                        bayeux.addListener(new ClientBayeuxListener()
                        {
                            public void clientAdded(Client client)
                            {
                            }

                            public void clientRemoved(Client client)
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
                        bayeux.getChannel(channel, false).addListener(new SubscriptionListener()
                        {
                            public void subscribed(Client client, Channel channel)
                            {
                            }

                            public void unsubscribed(Client client, Channel channel)
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
                return message;
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

        String clientId = extractClientId(handshake.getResponseContent());

        ContentExchange connect = newBayeuxExchange("[{" +
                                                    "\"channel\": \"/meta/connect\"," +
                                                    "\"clientId\": \"" + clientId + "\"," +
                                                    "\"connectionType\": \"long-polling\"" +
                                                    "}]");
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
