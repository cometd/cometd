package org.cometd.server;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;

public class ConcurrentHandshakeFailureSubscribePublishTest extends AbstractBayeuxClientServerTest
{
    private BayeuxServer bayeux;

    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        this.bayeux = bayeux;
        this.bayeux.setSecurityPolicy(new Policy());
    }

    public void testConcurrentHandshakeFailureAndSubscribe() throws Exception
    {
        final AtomicBoolean subscribe = new AtomicBoolean();
        new AbstractService(bayeux, "test")
        {
            {
                addService(Channel.META_SUBSCRIBE, "metaSubscribe");
            }

            public void metaSubscribe(ServerSession remote, Message message)
            {
                subscribe.set(true);
            }
        };

        // A bad sequence of messages that clients should prevent
        // (by not allowing a subscribe until the handshake is completed)
        // yet the server must behave properly
        String channelName = "/foo";
        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}, {" +
                "\"clientId\": null," +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"" + channelName + "\"" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        List<Message.Mutable> messages = HashMapMessage.parseMessages(handshake.getResponseContent());
        assertEquals(2, messages.size());
        Message handshakeResponse = messages.get(0);
        assertFalse(handshakeResponse.isSuccessful());
        String handshakeError = (String)handshakeResponse.get("error");
        assertNotNull(handshakeError);
        assertTrue(handshakeError.contains("403"));
        Map<String, Object> advice = handshakeResponse.getAdvice();
        assertNotNull(advice);
        assertEquals(Message.RECONNECT_NONE_VALUE, advice.get("reconnect"));
        Message subscribeResponse = messages.get(1);
        assertFalse(subscribeResponse.isSuccessful());
        String subscribeError = (String)subscribeResponse.get("error");
        assertNotNull(subscribeError);
        assertTrue(subscribeError.contains("402"));
        assertNull(subscribeResponse.getAdvice());

        assertFalse(subscribe.get());
    }

    public void testConcurrentHandshakeFailureAndPublish() throws Exception
    {
        final String channelName = "/foo";
        final AtomicBoolean publish = new AtomicBoolean();
        new AbstractService(bayeux, "test")
        {
            {
                addService(channelName, "process");
            }

            public void process(ServerSession remote, Message message)
            {
                publish.set(true);
            }
        };

        // A bad sequence of messages that clients should prevent
        // (by not allowing a publish until the handshake is completed)
        // yet the server must behave properly
        ContentExchange handshake = newBayeuxExchange("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}, {" +
                "\"clientId\": null," +
                "\"channel\": \"" + channelName + "\"," +
                "\"data\": {}" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        List<Message.Mutable> messages = HashMapMessage.parseMessages(handshake.getResponseContent());
        assertEquals(2, messages.size());
        Message handshakeResponse = messages.get(0);
        assertFalse(handshakeResponse.isSuccessful());
        String handshakeError = (String)handshakeResponse.get("error");
        assertNotNull(handshakeError);
        assertTrue(handshakeError.contains("403"));
        Map<String, Object> advice = handshakeResponse.getAdvice();
        assertNotNull(advice);
        assertEquals(Message.RECONNECT_NONE_VALUE, advice.get("reconnect"));
        Message publishResponse = messages.get(1);
        assertFalse(publishResponse.isSuccessful());
        String publishError = (String)publishResponse.get("error");
        assertNotNull(publishError);
        assertTrue(publishError.contains("402"));
        assertNull(publishResponse.getAdvice());

        assertFalse(publish.get());
    }

    private class Policy extends DefaultSecurityPolicy
    {
        @Override
        public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
        {
            if (session.isLocalSession())
                return true;
            Map<String,Object> ext = message.getExt();
            return ext != null && ext.get("authn") != null;
        }
    }
}
