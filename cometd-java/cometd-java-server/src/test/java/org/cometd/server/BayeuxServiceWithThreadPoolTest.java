package org.cometd.server;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxServiceWithThreadPoolTest extends AbstractBayeuxClientServerTest
{
    private BayeuxServerImpl bayeux;

    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        this.bayeux = bayeux;
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

        String clientId = extractClientId(handshake);

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
        assertNotNull(message.getData());
    }

    public static class TestService extends AbstractService
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Message message;

        public TestService(BayeuxServerImpl bayeux, String channel)
        {
            super(bayeux, "test", 5);
            addService(channel, "handle");
        }

        @Override
        protected void doInvoke(Method method, ServerSession fromClient, ServerMessage msg)
        {
            // Sleep for a while to simulate a slow dispatch
            sleep(500);
            // Save a copy of the message to test it later
            message = msg;
            super.doInvoke(method, fromClient, msg);
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

        public void handle(ServerSession remote, Message message)
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
