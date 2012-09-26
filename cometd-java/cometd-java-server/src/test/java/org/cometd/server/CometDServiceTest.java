package org.cometd.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.Assert;
import org.junit.Test;

public class CometDServiceTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testRemoveService() throws Exception
    {
        final String channel1 = "/foo";
        final String channel2 = "/bar";
        final AtomicReference<CountDownLatch> publishLatch1 = new AtomicReference<>(new CountDownLatch(1));
        final AtomicReference<CountDownLatch> publishLatch2 = new AtomicReference<>(new CountDownLatch(1));
        AbstractService service = new AbstractService(bayeux, "test_remove")
        {
            {
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
        };

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());

        String clientId = extractClientId(response);

        Request publish1 = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel1 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish1.send().get(5, TimeUnit.SECONDS);
        Assert.assertTrue(publishLatch1.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.status());

        Request publish2 = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel2 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish2.send().get(5, TimeUnit.SECONDS);
        Assert.assertTrue(publishLatch2.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.status());

        service.removeService(channel1, "one");
        publishLatch1.set(new CountDownLatch(1));
        publishLatch2.set(new CountDownLatch(1));

        publish1 = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel1 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish1.send().get(5, TimeUnit.SECONDS);
        Assert.assertFalse(publishLatch1.get().await(1, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.status());

        publish2 = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel2 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish2.send().get(5, TimeUnit.SECONDS);
        Assert.assertTrue(publishLatch2.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.status());

        service.removeService(channel2);
        publishLatch2.set(new CountDownLatch(1));

        publish2 = newBayeuxRequest("[{" +
                "\"channel\": \"" + channel2 + "\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"data\": {}" +
                "}]");
        response = publish2.send().get(5, TimeUnit.SECONDS);
        Assert.assertFalse(publishLatch2.get().await(1, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.status());

        Request disconnect = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        response = disconnect.send().get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.status());
    }
}
