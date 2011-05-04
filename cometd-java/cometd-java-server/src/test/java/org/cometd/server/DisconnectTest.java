package org.cometd.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;

public class DisconnectTest extends AbstractBayeuxClientServerTest
{
    private BayeuxServer bayeux;

    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        this.bayeux = bayeux;
    }

    public void testDisconnect() throws Exception
    {
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

        ServerSession serverSession = bayeux.getSession(clientId);
        assertNotNull(serverSession);

        final CountDownLatch latch = new CountDownLatch(1);
        serverSession.addListener(new ServerSession.RemoveListener()
        {
            public void removed(ServerSession session, boolean timeout)
            {
                latch.countDown();
            }
        });

        ContentExchange disconnect = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/disconnect\"," +
                "\"clientId\": \"" + clientId + "\"" +
                "}]");
        httpClient.send(disconnect);
        assertEquals(HttpExchange.STATUS_COMPLETED, disconnect.waitForDone());
        assertEquals(200, disconnect.getResponseStatus());

        assertTrue(latch.await(1, TimeUnit.SECONDS));
    }
}
