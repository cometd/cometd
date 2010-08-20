package org.cometd.server.ext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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

/**
 * @version $Revision$ $Date$
 */
public class ExtensionPublishReceivedTest extends AbstractBayeuxClientServerTest
{
    private CountingExtension extension;

    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
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

        String clientId = extractClientId(handshake);

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
        assertEquals(4, extension.rcvMetas.size());
        assertEquals(1, extension.sends.size());
        assertEquals(4, extension.sendMetas.size());
    }

    private class CountingExtension implements BayeuxServer.Extension
    {
        private final List<Message> rcvs = new ArrayList<Message>();
        private final List<Message> rcvMetas = new ArrayList<Message>();
        private final List<Message> sends = new ArrayList<Message>();
        private final List<Message> sendMetas = new ArrayList<Message>();

        public boolean rcv(ServerSession from, ServerMessage.Mutable message)
        {
            rcvs.add(message);
            return true;
        }

        public boolean rcvMeta(ServerSession from, ServerMessage.Mutable message)
        {
            rcvMetas.add(message);
            return true;
        }

        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message)
        {
            sends.add(message);
            return true;
        }

        public boolean sendMeta(ServerSession to, ServerMessage.Mutable message)
        {
            sendMetas.add(message);
            return true;
        }
    }

    public class Publisher extends AbstractService
    {
        public Publisher(BayeuxServer bayeux)
        {
            super(bayeux, "test");
            addService(Channel.META_SUBSCRIBE, "emit");
        }

        public void emit(ServerSession remote, Message message)
        {
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("emitted", true);
            remote.deliver(getServerSession(), "/test", data, null);
        }
    }
}
