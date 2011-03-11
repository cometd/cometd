package org.cometd.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.HashMapMessage;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;

public class HandshakeFailureCustomResponseTest extends AbstractBayeuxClientServerTest
{
    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        bayeux.setSecurityPolicy(new DefaultSecurityPolicy()
        {
            @Override
            public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
            {
                ServerMessage.Mutable handshakeReply = message.getAssociated();
                Map<String,Object> advice = handshakeReply.getAdvice(true);
                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);
                Map<String, Object> ext = handshakeReply.getExt(true);
                Map<String, Object> authentication = new HashMap<String, Object>();
                ext.put("com.acme.auth", authentication);
                authentication.put("failure", "test");
                return false;
            }
        });
    }

    public void testHandshakeFailureCustomResponse() throws Exception
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

        List<Message.Mutable> responses = HashMapMessage.parseMessages(handshake.getResponseContent());
        assertEquals(1, responses.size());
        Message response = responses.get(0);
        Map<String, Object> advice = response.getAdvice();
        assertNotNull(advice);
        assertEquals(Message.RECONNECT_HANDSHAKE_VALUE, advice.get(Message.RECONNECT_FIELD));
        Map<String, Object> ext = response.getExt();
        assertNotNull(ext);
        Map<String, Object> authentication = (Map<String, Object>)ext.get("com.acme.auth");
        assertNotNull(authentication);
        assertEquals("test", authentication.get("failure"));
    }
}
