package org.cometd.server.transport;

import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.common.HashMapMessage;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;

public class JSONTransportMetaConnectDeliveryTest extends AbstractBayeuxClientServerTest
{
    @Override
    protected void customizeOptions(Map<String, String> options)
    {
        options.put("long-polling.json.metaConnectDeliverOnly", "true");
    }

    public void testJSONTransportMetaConnectDelivery() throws Exception
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

        String channel = "/foo";

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

        // Expect only the meta response to the publish
        List<Message.Mutable> messages = HashMapMessage.parseMessages(publish.getResponseContent());
        assertEquals(1, messages.size());

        connect = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/connect\"," +
                "\"clientId\": \"" + clientId + "\"," +
                "\"connectionType\": \"long-polling\"" +
                "}]");
        connect.setRequestHeader(HttpHeaders.COOKIE, bayeuxCookie);
        httpClient.send(connect);
        assertEquals(HttpExchange.STATUS_COMPLETED, connect.waitForDone());
        assertEquals(200, connect.getResponseStatus());

        // Expect meta response to the connect plus the published message
        messages = HashMapMessage.parseMessages(connect.getResponseContent());
        assertEquals(2, messages.size());
    }
}
