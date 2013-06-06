package org.cometd.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CometDServiceTest extends AbstractBayeuxClientServerTest
{
    @Before
    public void prepare() throws Exception
    {
        startServer(null);
    }

    @Test
    public void testRemoveService() throws Exception
    {
        final String channel1 = "/foo";
        final String channel2 = "/bar";
        final AtomicReference<CountDownLatch> publishLatch1 = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> publishLatch2 = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        AbstractService service = new OneTwoService(bayeux, channel1, channel2, publishLatch1, publishLatch2);

        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        Assert.assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);

        ContentExchange publish1 = newBayeuxExchange("[{" +
                "\"channel\": \"" + channel1 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish1);
        Assert.assertTrue(publishLatch1.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, publish1.waitForDone());
        Assert.assertEquals(200, publish1.getResponseStatus());

        ContentExchange publish2 = newBayeuxExchange("[{" +
                "\"channel\": \"" + channel2 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish2);
        Assert.assertTrue(publishLatch2.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, publish2.waitForDone());
        Assert.assertEquals(200, publish2.getResponseStatus());

        service.removeService(channel1, "one");
        publishLatch1.set(new CountDownLatch(1));
        publishLatch2.set(new CountDownLatch(1));

        publish1 = newBayeuxExchange("[{" +
                "\"channel\": \"" + channel1 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish1);
        Assert.assertFalse(publishLatch1.get().await(1, TimeUnit.SECONDS));
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, publish1.waitForDone());
        Assert.assertEquals(200, publish1.getResponseStatus());

        publish2 = newBayeuxExchange("[{" +
                "\"channel\": \"" + channel2 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish2);
        Assert.assertTrue(publishLatch2.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, publish2.waitForDone());
        Assert.assertEquals(200, publish2.getResponseStatus());

        service.removeService(channel2);
        publishLatch2.set(new CountDownLatch(1));

        publish2 = newBayeuxExchange("[{" +
                "\"channel\": \"" + channel2 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(publish2);
        Assert.assertFalse(publishLatch2.get().await(1, TimeUnit.SECONDS));
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, publish2.waitForDone());
        Assert.assertEquals(200, publish2.getResponseStatus());

        ContentExchange disconnect = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        httpClient.send(disconnect);
        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, disconnect.waitForDone());
        Assert.assertEquals(200, disconnect.getResponseStatus());
    }

    public static class OneTwoService extends AbstractService
    {
        private final AtomicReference<CountDownLatch> publishLatch1;
        private final AtomicReference<CountDownLatch> publishLatch2;

        public OneTwoService(BayeuxServerImpl bayeux, String channel1, String channel2, AtomicReference<CountDownLatch> publishLatch1, AtomicReference<CountDownLatch> publishLatch2)
        {
            super(bayeux, "test_remove");
            this.publishLatch1 = publishLatch1;
            this.publishLatch2 = publishLatch2;
            addService(channel1, "one");
            addService(channel2, "two");
        }

        public void one(ServerSession remote, ServerMessage.Mutable message)
        {
            publishLatch1.get().countDown();
        }

        public void two(ServerSession remote, ServerMessage.Mutable message)
        {
            publishLatch2.get().countDown();
        }
    }
}
