package org.cometd.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.TestCase;
import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.cometd.server.AbstractBayeux;
import org.cometd.server.continuation.ContinuationCometdServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * @version $Revision$ $Date$
 */
public class MessageDeepCopyTest extends TestCase
{
    private Server server;
    private String cometdURL;
    private HttpClient httpClient;
    private AbstractBayeux bayeux;

    @Override
    protected void setUp() throws Exception
    {
        server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/cometd";
        ServletContextHandler context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        // Setup CometD servlet
        ContinuationCometdServlet cometdServlet = new ContinuationCometdServlet();
        ServletHolder cometServletHolder = new ServletHolder(cometdServlet);
        cometServletHolder.setInitParameter("timeout", String.valueOf(5000));
        cometServletHolder.setInitParameter("logLevel", "2");
        cometServletHolder.setInitParameter("requestAvailable", "true");
        String cometServletPath = "/cometd";
        context.addServlet(cometServletHolder, cometServletPath + "/*");

        server.start();

        bayeux = cometdServlet.getBayeux();
        bayeux.addExtension(new BayeuxMessageDeepCopyExtension());

        String contextURL = "http://localhost:" + connector.getLocalPort() + contextPath;
        cometdURL = contextURL + cometServletPath;

        httpClient = new HttpClient();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        httpClient.setIdleTimeout(10000);
        httpClient.start();
    }

    @Override
    protected void tearDown() throws Exception
    {
        httpClient.stop();
        server.stop();
        server.join();
    }

    public void testMessageDeepCopy() throws Exception
    {
        final String channel = "/test";

        final CountDownLatch readyLatch = new CountDownLatch(2);
        final CountDownLatch messagesLatch = new CountDownLatch(2);
        final AtomicReference<Message> adminMessage = new AtomicReference<Message>();
        final AtomicReference<Message> standardMessage = new AtomicReference<Message>();

        BayeuxClient adminClient = new BayeuxClient(httpClient, cometdURL);
        adminClient.addExtension(new AdminExtension());
        adminClient.start();
        adminClient.addListener(new MessageListener()
        {
            public void deliver(Client from, Client to, Message message)
            {
                if (Bayeux.META_SUBSCRIBE.equals(message.getChannel()))
                {
                    readyLatch.countDown();
                }
                else if (channel.equals(message.getChannel()) && message.getData() != null)
                {
                    // Deep copy the message otherwise it is recycled and the test fails
                    adminMessage.set(deepCopy(message));
                    messagesLatch.countDown();
                }
            }
        });
        adminClient.subscribe(channel);

        BayeuxClient standardClient = new BayeuxClient(httpClient, cometdURL);
        standardClient.start();
        standardClient.addListener(new MessageListener()
        {
            public void deliver(Client from, Client to, Message message)
            {
                if (Bayeux.META_SUBSCRIBE.equals(message.getChannel()))
                {
                    readyLatch.countDown();
                }
                else if (channel.equals(message.getChannel()) && message.getData() != null)
                {
                    // Deep copy the message otherwise it is recycled and the test fails
                    standardMessage.set(deepCopy(message));
                    messagesLatch.countDown();
                }
            }
        });
        standardClient.subscribe(channel);

        assertTrue(readyLatch.await(1000, TimeUnit.MILLISECONDS));

        Map<String, Object> data = new HashMap<String, Object>();
        adminClient.publish(channel, data, null);

        assertTrue(messagesLatch.await(1000, TimeUnit.MILLISECONDS));
        assertNotNull(adminMessage.get());
        assertNotNull(standardMessage.get());
        Message admin = adminMessage.get();
        assertNotNull(admin.getExt(false));
        assertTrue((Boolean)admin.getExt(false).get("secret"));
        Message standard = standardMessage.get();
        assertNull(standard.getExt(false));

        adminClient.stop();
        standardClient.stop();
    }

    private Message deepCopy(Message message)
    {
        String json = bayeux.getMsgJSON().toJSON(message);
        return (Message)bayeux.getMsgJSON().fromJSON(json);
    }

    /**
     * Client-side extension that identify a client as having an admin role
     */
    private static class AdminExtension implements Extension
    {
        public Message rcv(Client client, Message message)
        {
            return message;
        }

        public Message rcvMeta(Client client, Message message)
        {
            return message;
        }

        public Message send(Client client, Message message)
        {
            return message;
        }

        public Message sendMeta(Client client, Message message)
        {
            if (Bayeux.META_HANDSHAKE.equals(message.getChannel()))
            {
                Map<String, Object> ext = message.getExt(true);
                Map<String, Object> role = new HashMap<String, Object>();
                ext.put("role", role);
                role.put("name", "admin");
                message.put(Bayeux.EXT_FIELD, ext);
            }
            return message;
        }
    }

    /**
     * Server-side extension, per-Bayeux, that attaches the per-Client extension to server-side Client objects
     */
    private class BayeuxMessageDeepCopyExtension implements Extension
    {
        public Message rcv(Client client, Message message)
        {
            return message;
        }

        public Message rcvMeta(Client client, Message message)
        {
            return message;
        }

        public Message send(Client client, Message message)
        {
            return message;
        }

        public Message sendMeta(Client client, Message message)
        {
            if (Bayeux.META_HANDSHAKE.equals(message.getChannel()))
            {
                if (message.get(Bayeux.SUCCESSFUL_FIELD) == Boolean.TRUE)
                {
                    boolean admin = false;

                    Map<String, Object> ext = message.getAssociated().getExt(false);
                    if (ext != null)
                    {
                        Map<String, Object> role = (Map<String, Object>)ext.get("role");
                        if (role != null)
                        {
                            String name = (String)role.get("name");
                            if ("admin".equals(name))
                            {
                                admin = true;
                            }
                        }
                    }

                    client.addExtension(new ClientMessageDeepCopyExtension(admin));
                }
            }
            return message;
        }
    }

    /**
     * Server-side extension, per-Client, that deep copies messages to return extra information
     * only if the client has an admin role
     */
    private class ClientMessageDeepCopyExtension implements Extension
    {
        private final boolean admin;

        public ClientMessageDeepCopyExtension(boolean admin)
        {
            this.admin = admin;
        }

        public Message rcv(Client client, Message message)
        {
            return message;
        }

        public Message rcvMeta(Client client, Message message)
        {
            return message;
        }

        public Message send(Client client, Message message)
        {
            if (admin)
            {
                // Deep copy the message
                Message newMessage = deepCopy(message);
                Map<String, Object> ext = newMessage.getExt(true);
                ext.put("secret", true);
                newMessage.put(Bayeux.EXT_FIELD, ext);
                return newMessage;
            }
            return message;
        }

        public Message sendMeta(Client client, Message message)
        {
            return message;
        }
    }
}
