package org.cometd.server.ext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.util.ajax.JSON;

public class BayeuxExtensionTest extends AbstractBayeuxClientServerTest
{
    private static final String CLIENT_MESSAGE_FIELD = "clientMessageField";
    private static final String CLIENT_EXT_FIELD = "clientExtField";
    private static final String CLIENT_DATA_FIELD = "clientDataField";
    private static final String SERVER_EXT_MESSAGE_FIELD = "serverExtMessageField";
    private static final String SERVER_EXT_DATA_FIELD = "serverExtDataField";
    private static final String SERVER_EXT_EXT_FIELD = "serverExtExtField";
    private static final String SERVER_MESSAGE_FIELD = "serverMessageField";
    private static final String SERVER_DATA_FIELD = "serverDataField";
    private static final String SERVER_EXT_FIELD = "serverExtField";
    private static final String SERVER_EXT_INFO = "fromExtension";
    private static final String CLIENT_INFO = "fromClient";
    private static final String SERVICE_INFO = "fromService";

    private BayeuxServerImpl bayeux;

    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        this.bayeux = bayeux;
    }

    public void testBayeuxExtensionOnHandshake() throws Exception
    {
        bayeux.addExtension(new MetaExtension());
        final AtomicReference<ServerMessage> handshakeRef = new AtomicReference<ServerMessage>();
        new AbstractService(bayeux, "test")
        {
            {
                addService(Channel.META_HANDSHAKE, "metaHandshake");
            }

            public void metaHandshake(ServerSession remote, ServerMessage message)
            {
                handshakeRef.set(message);
            }
        };

        ContentExchange handshake = newBayeuxExchange("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]," +
                "\"" + CLIENT_MESSAGE_FIELD + "\": \"" + CLIENT_INFO + "\"," +
                "\"ext\": { \"" + CLIENT_EXT_FIELD + "\": \"" + CLIENT_INFO + "\" }," +
                "\"data\": { \"" + CLIENT_DATA_FIELD + "\": \"" + CLIENT_INFO + "\" }" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        assertEquals(SERVER_EXT_INFO, handshakeRef.get().get(SERVER_EXT_MESSAGE_FIELD));
        assertEquals(SERVER_EXT_INFO, handshakeRef.get().getDataAsMap().get(SERVER_EXT_DATA_FIELD));
        assertEquals(SERVER_EXT_INFO, handshakeRef.get().getExt().get(SERVER_EXT_EXT_FIELD));
        assertEquals(CLIENT_INFO, handshakeRef.get().get(CLIENT_MESSAGE_FIELD));
        assertEquals(CLIENT_INFO, handshakeRef.get().getExt().get(CLIENT_EXT_FIELD));
        assertEquals(CLIENT_INFO, handshakeRef.get().getDataAsMap().get(CLIENT_DATA_FIELD));

        Object[] messages = (Object[])JSON.parse(handshake.getResponseContent());
        assertEquals(1, messages.length);
        Map message = (Map)messages[0];
        assertEquals(SERVER_EXT_INFO, message.get(SERVER_EXT_MESSAGE_FIELD));
        assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.DATA_FIELD)).get(SERVER_EXT_DATA_FIELD));
        assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.EXT_FIELD)).get(SERVER_EXT_EXT_FIELD));
    }

    public void testBayeuxExtensionOnServiceChannel() throws Exception
    {
        final String channel = "/service/test";
        bayeux.addExtension(new NonMetaExtension(channel));
        final AtomicReference<ServerMessage> publishRef = new AtomicReference<ServerMessage>();
        new AbstractService(bayeux, "test")
        {
            {
                addService(channel, "test");
            }

            public void test(ServerSession remote, ServerMessage message)
            {
                publishRef.set(message);

                ServerMessage.Mutable response = getBayeux().newMessage();
                response.put(SERVER_MESSAGE_FIELD, SERVICE_INFO);
                response.getDataAsMap(true).put(SERVER_DATA_FIELD, SERVICE_INFO);
                response.getExt(true).put(SERVER_EXT_FIELD, SERVICE_INFO);
                remote.deliver(getServerSession(), response);
            }
        };

        ContentExchange handshake = newBayeuxExchange("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);

        ContentExchange publish = newBayeuxExchange("" +
                "[{" +
                "\"clientId\": \"" + clientId + "\"," +
                "\"channel\": \"" + channel + "\"," +
                "\"" + CLIENT_MESSAGE_FIELD + "\": \"" + CLIENT_INFO + "\"," +
                "\"ext\": { \"" + CLIENT_EXT_FIELD + "\": \"" + CLIENT_INFO + "\" }," +
                "\"data\": { \"" + CLIENT_DATA_FIELD + "\": \"" + CLIENT_INFO + "\" }" +
                "}]");
        httpClient.send(publish);
        assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        assertEquals(200, publish.getResponseStatus());

        assertEquals(SERVER_EXT_INFO, publishRef.get().get(SERVER_EXT_MESSAGE_FIELD));
        assertEquals(SERVER_EXT_INFO, publishRef.get().getDataAsMap().get(SERVER_EXT_DATA_FIELD));
        assertEquals(SERVER_EXT_INFO, publishRef.get().getExt().get(SERVER_EXT_EXT_FIELD));
        assertEquals(CLIENT_INFO, publishRef.get().get(CLIENT_MESSAGE_FIELD));
        assertEquals(CLIENT_INFO, publishRef.get().getExt().get(CLIENT_EXT_FIELD));
        assertEquals(CLIENT_INFO, publishRef.get().getDataAsMap().get(CLIENT_DATA_FIELD));

        Object[] messages = (Object[])JSON.parse(publish.getResponseContent());
        assertEquals(2, messages.length);
        Map message = ((Map)messages[0]).containsKey(Message.DATA_FIELD) ? (Map)messages[0] : (Map)messages[1];
        assertEquals(SERVER_EXT_INFO, message.get(SERVER_EXT_MESSAGE_FIELD));
        assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.DATA_FIELD)).get(SERVER_EXT_DATA_FIELD));
        assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.EXT_FIELD)).get(SERVER_EXT_EXT_FIELD));
        assertEquals(SERVICE_INFO, message.get(SERVER_MESSAGE_FIELD));
        assertEquals(SERVICE_INFO, ((Map)message.get(Message.DATA_FIELD)).get(SERVER_DATA_FIELD));
        assertEquals(SERVICE_INFO, ((Map)message.get(Message.EXT_FIELD)).get(SERVER_EXT_FIELD));
    }

    public void testBayeuxExtensionOnBroadcastChannel() throws Exception
    {
        final String channel = "/test";
        bayeux.addExtension(new NonMetaExtension(channel));
        final AtomicReference<ServerMessage> publishRef = new AtomicReference<ServerMessage>();
        new AbstractService(bayeux, "test")
        {
            {
                addService(channel, "test");
            }

            public void test(ServerSession remote, ServerMessage.Mutable message)
            {
                message.put(SERVER_MESSAGE_FIELD, SERVICE_INFO);
                message.getDataAsMap(true).put(SERVER_DATA_FIELD, SERVICE_INFO);
                message.getExt(true).put(SERVER_EXT_FIELD, SERVICE_INFO);
                publishRef.set(message);
            }
        };

        ContentExchange handshake = newBayeuxExchange("" +
                "[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        httpClient.send(handshake);
        assertEquals(HttpExchange.STATUS_COMPLETED, handshake.waitForDone());
        assertEquals(200, handshake.getResponseStatus());

        String clientId = extractClientId(handshake);

        ContentExchange subscribe = newBayeuxExchange("" +
                "[{" +
                "\"clientId\": \"" + clientId + "\"," +
                "\"channel\": \"/meta/subscribe\"," +
                "\"subscription\": \"" + channel + "\"," +
                "}]");
        httpClient.send(subscribe);
        assertEquals(HttpExchange.STATUS_COMPLETED, subscribe.waitForDone());
        assertEquals(200, subscribe.getResponseStatus());

        ContentExchange publish = newBayeuxExchange("" +
                "[{" +
                "\"clientId\": \"" + clientId + "\"," +
                "\"channel\": \"" + channel + "\"," +
                "\"" + CLIENT_MESSAGE_FIELD + "\": \"" + CLIENT_INFO + "\"," +
                "\"ext\": { \"" + CLIENT_EXT_FIELD + "\": \"" + CLIENT_INFO + "\" }," +
                "\"data\": { \"" + CLIENT_DATA_FIELD + "\": \"" + CLIENT_INFO + "\" }" +
                "}]");
        httpClient.send(publish);
        assertEquals(HttpExchange.STATUS_COMPLETED, publish.waitForDone());
        assertEquals(200, publish.getResponseStatus());

        assertEquals(SERVER_EXT_INFO, publishRef.get().get(SERVER_EXT_MESSAGE_FIELD));
        assertEquals(SERVER_EXT_INFO, publishRef.get().getDataAsMap().get(SERVER_EXT_DATA_FIELD));
        assertEquals(SERVER_EXT_INFO, publishRef.get().getExt().get(SERVER_EXT_EXT_FIELD));
        assertEquals(CLIENT_INFO, publishRef.get().get(CLIENT_MESSAGE_FIELD));
        assertEquals(CLIENT_INFO, publishRef.get().getExt().get(CLIENT_EXT_FIELD));
        assertEquals(CLIENT_INFO, publishRef.get().getDataAsMap().get(CLIENT_DATA_FIELD));

        Object[] messages = (Object[])JSON.parse(publish.getResponseContent());
        assertEquals(2, messages.length);
        Map message = ((Map)messages[0]).containsKey(Message.DATA_FIELD) ? (Map)messages[0] : (Map)messages[1];
        assertEquals(SERVER_EXT_INFO, message.get(SERVER_EXT_MESSAGE_FIELD));
        assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.DATA_FIELD)).get(SERVER_EXT_DATA_FIELD));
        assertEquals(SERVER_EXT_INFO, ((Map)message.get(Message.EXT_FIELD)).get(SERVER_EXT_EXT_FIELD));
        assertEquals(SERVICE_INFO, message.get(SERVER_MESSAGE_FIELD));
        assertEquals(SERVICE_INFO, ((Map)message.get(Message.DATA_FIELD)).get(SERVER_DATA_FIELD));
        assertEquals(SERVICE_INFO, ((Map)message.get(Message.EXT_FIELD)).get(SERVER_EXT_FIELD));
    }

    private class MetaExtension implements BayeuxServer.Extension
    {
        public boolean rcv(ServerSession from, ServerMessage.Mutable message)
        {
            return true;
        }

        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message)
        {
            if (Channel.META_HANDSHAKE.equals(message.getChannel()) && (from == null || !from.isLocalSession()))
            {
                message.put(SERVER_EXT_MESSAGE_FIELD, SERVER_EXT_INFO);
                message.getDataAsMap(true).put(SERVER_EXT_DATA_FIELD, SERVER_EXT_INFO);
                message.getExt(true).put(SERVER_EXT_EXT_FIELD, SERVER_EXT_INFO);
            }
            return true;
        }

        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message)
        {
            return true;
        }

        public boolean sendMeta(ServerSession to, ServerMessage.Mutable message)
        {
            if (Channel.META_HANDSHAKE.equals(message.getChannel()) && to != null && !to.isLocalSession())
            {
                message.put(SERVER_EXT_MESSAGE_FIELD, SERVER_EXT_INFO);
                message.getDataAsMap(true).put(SERVER_EXT_DATA_FIELD, SERVER_EXT_INFO);
                message.getExt(true).put(SERVER_EXT_EXT_FIELD, SERVER_EXT_INFO);
            }
            return true;
        }
    }

    private class NonMetaExtension implements BayeuxServer.Extension
    {
        private final String channel;

        private NonMetaExtension(String channel)
        {
            this.channel = channel;
        }

        public boolean rcv(ServerSession from, ServerMessage.Mutable message)
        {
            if (from != null && !from.isLocalSession() && channel.equals(message.getChannel()))
            {
                message.put(SERVER_EXT_MESSAGE_FIELD, SERVER_EXT_INFO);
                message.getDataAsMap(true).put(SERVER_EXT_DATA_FIELD, SERVER_EXT_INFO);
                message.getExt(true).put(SERVER_EXT_EXT_FIELD, SERVER_EXT_INFO);
            }
            return true;
        }

        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message)
        {
            return true;
        }

        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message)
        {
            if (from != null && from.isLocalSession() && to != null && !to.isLocalSession())
            {
                message.put(SERVER_EXT_MESSAGE_FIELD, SERVER_EXT_INFO);
                message.getDataAsMap(true).put(SERVER_EXT_DATA_FIELD, SERVER_EXT_INFO);
                message.getExt(true).put(SERVER_EXT_EXT_FIELD, SERVER_EXT_INFO);
            }
            return true;
        }

        public boolean sendMeta(ServerSession to, ServerMessage.Mutable message)
        {
            return true;
        }
    }
}
