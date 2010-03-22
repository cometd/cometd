package org.cometd.server.ext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;
import org.cometd.server.AbstractBayeuxClientServerTest;
import org.cometd.server.BayeuxService;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpExchange;

/**
 * @version $Revision$ $Date$
 */
public class ExtensionPublishReceivedTest extends AbstractBayeuxClientServerTest
{
    private CountingExtension extension;

    @Override
    protected void customizeBayeux(Bayeux bayeux)
    {
        extension = new CountingExtension();
        bayeux.addExtension(extension);
        new Publisher(bayeux);
    }

    public void testExtension() throws Exception
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

        String clientId = extractClientId(handshake.getResponseContent());

        String channel = "/test";
        ContentExchange subscribe = newBayeuxExchange("[{" +
                                                   "\"channel\": \"/meta/subscribe\"," +
                                                   "\"clientId\": \"" + clientId + "\"," +
                                                   "\"subscription\": \"" + channel + "\"" +
                                                   "}]");
        httpClient.send(subscribe);
        assertEquals(HttpExchange.STATUS_COMPLETED, subscribe.waitForDone());
        assertEquals(200, subscribe.getResponseStatus());

        assertEquals(0, extension.rcvs.size());
        assertEquals(0, extension.rcvMetas.size());
        assertEquals(1, extension.sends.size());
        assertEquals(0, extension.sendMetas.size());
    }

    private class CountingExtension implements Extension
    {
        private final List<Message> rcvs = new ArrayList<Message>();
        private final List<Message> rcvMetas = new ArrayList<Message>();
        private final List<Message> sends = new ArrayList<Message>();
        private final List<Message> sendMetas = new ArrayList<Message>();

        public Message rcv(Client client, Message message)
        {
            rcvs.add(message);
            return message;
        }

        public Message rcvMeta(Client client, Message message)
        {
            if (!Bayeux.META_HANDSHAKE.equals(message.getChannel()) &&
                    !Bayeux.META_SUBSCRIBE.equals(message.getChannel()))
            {
                rcvMetas.add(message);
            }
            return message;
        }

        public Message send(Client client, Message message)
        {
            sends.add(message);
            return message;
        }

        public Message sendMeta(Client client, Message message)
        {
            if (!Bayeux.META_HANDSHAKE.equals(message.getChannel()) &&
                    !Bayeux.META_SUBSCRIBE.equals(message.getChannel()))
            {
                sendMetas.add(message);
            }
            return message;
        }
    }

    public class Publisher extends BayeuxService
    {
        public Publisher(Bayeux bayeux)
        {
            super(bayeux, "test");
            subscribe(Bayeux.META_SUBSCRIBE, "emit");
        }

        public void emit(Client remote, Message message)
        {
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("emitted", true);
            remote.deliver(getClient(), "/test", data, null);
        }
    }
}
