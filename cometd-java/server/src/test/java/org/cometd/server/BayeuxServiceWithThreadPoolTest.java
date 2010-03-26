package org.cometd.server;

import java.lang.reflect.Method;
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
public class BayeuxServiceWithThreadPoolTest extends AbstractBayeuxClientServerTest
{
    private AbstractBayeux bayeux;

    protected void customizeBayeux(Bayeux bayeux)
    {
        this.bayeux = (AbstractBayeux)bayeux;
    }

    public void testBayeuxServiceWithThreadPool() throws Exception
    {
        final String channel = "/foo";

        TestService service = new TestService(bayeux, channel);

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

        ContentExchange subscribe = newBayeuxExchange("[{" +
                                                   "\"channel\": \"/meta/subscribe\"," +
                                                   "\"clientId\": \"" + clientId + "\"," +
                                                   "\"subscription\": \"" + channel + "\"" +
                                                   "}]");
        httpClient.send(subscribe);
        assertEquals(HttpExchange.STATUS_COMPLETED, subscribe.waitForDone());
        assertEquals(200, subscribe.getResponseStatus());

        ContentExchange publish = newBayeuxExchange("[{" +
                                                    "\"channel\": \"" + channel + "\"," +
                                                    "\"clientId\": \"" + clientId + "\"," +
                                                    "\"data\": {}" +
                                                    "}]");
        httpClient.send(publish);
        assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        assertEquals(200, publish.getResponseStatus());

        assertTrue(service.await(1000));

        Message message = service.getMessage();
        assertNotNull(message);
        assertNotNull(message.getChannel());
        assertNotNull(message.getClientId());
        assertNotNull(message.getData());
    }

    public static class TestService extends BayeuxService
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Message message;

        public TestService(AbstractBayeux bayeux, String channel)
        {
            super(bayeux, "test", 5, false);
            subscribe(channel, "handle");
        }

        @Override
        protected void asyncDoInvoke(Method method, Client fromClient, Client toClient, Message msg)
        {
            // Sleep for a while to simulate a slow dispatch
            sleep(500);
            // Save a copy of the message to test it later
            // We need to deep copy it otherwise gets recycled before we can test it
            message = deepCopy(msg);
            super.asyncDoInvoke(method, fromClient, toClient, msg);
            latch.countDown();
        }

        private void sleep(long time)
        {
            try
            {
                Thread.sleep(time);
            }
            catch (InterruptedException x)
            {
                Thread.currentThread().interrupt();
            }
        }

        private Message deepCopy(Message msg)
        {
            AbstractBayeux bayeux = (AbstractBayeux)getBayeux();
            return (Message)bayeux.getMsgJSON().fromJSON(bayeux.getMsgJSON().toJSON(msg));
        }

        public void handle(Client remote, Message message)
        {
        }

        public boolean await(long time) throws InterruptedException
        {
            return latch.await(time, TimeUnit.MILLISECONDS);
        }

        public Message getMessage()
        {
            return message;
        }
    }
}
