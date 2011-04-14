package org.cometd.oort;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>The component that Searches for Extra Terrestrial Intelligence or,
 * in this case, just searches for a user logged onto a comet in an Oort cluster.</p>
 * <p>Seti allows an application to maintain a mapping from userId (any application
 * identifier such as user names or database IDs that represent users) to
 * server sessions using the {@link #associate(String, ServerSession)} and
 * {@link #disassociate(String)} methods.</p>
 * <p>A typical example of usage of {@link Seti#associate(String, ServerSession)} is
 * in a {@link SecurityPolicy} after a successful handshake (where authentication
 * information can be linked with the server session), or in {@link AbstractService CometD services}
 * where the association is established upon receiving a message on a particular channel
 * processed by the service itself.</p>
 * <p>Each comet in the cluster keeps its own mapping for clients connected to it.</p>
 * <p>The {@link #sendMessage(Collection, String, Object)} and
 * {@link #sendMessage(String, String, Object)} methods may be
 * used to send messages to user(s) anywhere in the Oort cluster
 * and Seti organizes the search in order to locate the user(s).</p>
 *
 * @see SetiServlet
 */
public class Seti extends AbstractLifeCycle
{
    public static final String SETI_ATTRIBUTE = Seti.class.getName();
    private static final String SETI_ALL_CHANNEL = "/seti/all";

    private final ConcurrentMap<String, Location> _uid2Location = new ConcurrentHashMap<String, Location>();
    private final Logger _logger;
    private final Oort _oort;
    private final String _setiId;
    private final LocalSession _session;

    public Seti(Oort oort)
    {
        _logger = Log.getLogger("Seti-" + oort.getURL());
        _oort = oort;
        _setiId = oort.getURL().replace("://", "_").replace(":", "_").replace("/", "_");
        _session = oort.getBayeuxServer().newLocalSession("seti");
    }

    protected Logger getLogger()
    {
        return _logger;
    }

    public Oort getOort()
    {
        return _oort;
    }

    public String getId()
    {
        return _setiId;
    }

    @Override
    protected void doStart() throws Exception
    {
        BayeuxServer bayeux = _oort.getBayeuxServer();
        bayeux.createIfAbsent("/seti/**", new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
            }
        });

        String channel = "/seti/" + _setiId;
        bayeux.createIfAbsent(channel, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setPersistent(true);
            }
        });
        _oort.observeChannel(channel);

        _session.handshake();

        _session.getChannel(channel).subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                receiveDirect(message);
            }
        });

        bayeux.createIfAbsent(SETI_ALL_CHANNEL, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setPersistent(true);
            }
        });
        _oort.observeChannel(SETI_ALL_CHANNEL);
        _session.getChannel(SETI_ALL_CHANNEL).subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                receiveBroadcast(message);
            }
        });
    }

    @Override
    protected void doStop() throws Exception
    {
        _session.disconnect();

        BayeuxServer bayeux = _oort.getBayeuxServer();
        _oort.deobserveChannel(SETI_ALL_CHANNEL);
        bayeux.getChannel(SETI_ALL_CHANNEL).setPersistent(false);

        String channel = "/seti/" + _setiId;
        _oort.deobserveChannel(channel);
        bayeux.getChannel(channel).setPersistent(false);

        bayeux.getChannel("/seti/**").removeAuthorizer(GrantAuthorizer.GRANT_ALL);
    }

    public void associate(final String userId, final ServerSession session)
    {
        if (session == null)
            throw new NullPointerException();

        _uid2Location.put(userId, new LocalLocation(session));
        _logger.debug("Associated session {} to user {}", session, userId);
        // Let everyone in the cluster know that this session is here
        _oort.getBayeuxServer().getChannel(SETI_ALL_CHANNEL).publish(_session, new SetiPresence(userId, true), null);
    }

    public void disassociate(final String userId)
    {
        Location location = _uid2Location.remove(userId);
        _logger.debug("Disassociated session {} from user {}", location, userId);
        // Let everyone in the cluster know that this session is not here anymore
        _oort.getBayeuxServer().getChannel(SETI_ALL_CHANNEL).publish(_session, new SetiPresence(userId, false), null);
    }

    public void sendMessage(final String toUser, final String toChannel, final Object data)
    {
        sendMessage(Collections.singleton(toUser), toChannel, data);
    }

    public void sendMessage(final Collection<String> toUsers, final String toChannel, final Object data)
    {
        for (String toUser : toUsers)
        {
            Location location = _uid2Location.get(toUser);
            if (location == null)
                location = new SetiLocation(SETI_ALL_CHANNEL);
            location.send(toUser, toChannel, data);
        }
    }

    /**
     * <p>Receives messages directly from other comets in the cloud, containing
     * messages to be sent to sessions that are connected to this comet.</p>
     *
     * @param message the message to forward to a session connected to this comet
     */
    protected void receiveDirect(Message message)
    {
        _logger.debug("Received direct message {}", message);
        receiveMessage(message);
    }

    protected void receiveBroadcast(Message message)
    {
        Map<String, Object> data = message.getDataAsMap();
        Boolean presence = (Boolean)data.get(SetiPresence.PRESENCE_FIELD);
        if (presence != null)
        {
            receivePresence(message);
        }
        else
        {
            receiveMessage(message);
        }
    }

    protected void receivePresence(Message message)
    {
        Map<String, Object> data = message.getDataAsMap();
        String setiId = (String)data.get(SetiPresence.SETI_ID_FIELD);
        if (_setiId.equals(setiId))
            return;

        _logger.debug("Received presence message {}", message);

        String userId = (String)data.get(SetiPresence.USER_ID_FIELD);
        boolean presence = (Boolean)data.get(SetiPresence.PRESENCE_FIELD);
        if (presence)
        {
            _uid2Location.put(userId, new SetiLocation("/seti/" + setiId));
        }
        else
        {
            _uid2Location.remove(userId);
        }
    }

    protected void receiveMessage(Message message)
    {
        Map<String, Object> messageData = message.getDataAsMap();
        String userId = (String)messageData.get(SetiMessage.USER_ID_FIELD);
        Location location = _uid2Location.get(userId);
        _logger.debug("Received message {} for location {}", message, location);
        if (location != null)
        {
            String channel = (String)messageData.get(SetiMessage.CHANNEL_FIELD);
            Object data = messageData.get(SetiMessage.DATA_FIELD);
            location.receive(userId, channel, data);
        }
    }

    protected interface Location
    {
        public void send(String toUser, String toChannel, Object data);

        public void receive(String toUser, String toChannel, Object data);
    }

    /**
     * A location that represent session connected to a local comet
     */
    protected class LocalLocation implements Location
    {
        private final ServerSession _session;

        protected LocalLocation(ServerSession session)
        {
            _session = session;
        }

        public void send(String toUser, String toChannel, Object data)
        {
            _session.deliver(Seti.this._session.getServerSession(), toChannel, data, null);
        }

        public void receive(String toUser, String toChannel, Object data)
        {
            send(toUser, toChannel, data);
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "[" + _session + "]";
        }
    }

    protected class SetiLocation implements Location
    {
        private final String _channel;

        protected SetiLocation(String channelId)
        {
            _channel = channelId;
        }

        public void send(String toUser, String toChannel, Object data)
        {
            _session.getChannel(_channel).publish(new SetiMessage(toUser, toChannel, data));
        }

        public void receive(String toUser, String toChannel, Object data)
        {
            // A message has been sent to this comet because the sender thought
            // the user was in this node. If it were, we would have found a
            // LocalLocation, but instead found this SetiLocation.
            // Therefore, the user must have moved to this seti location, and
            // we forward the message.
            send(toUser, toChannel, data);
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "[" + _channel + "]";
        }
    }

    private class SetiMessage extends HashMap<String, Object>
    {
        private static final String USER_ID_FIELD = "uid";
        private static final String CHANNEL_FIELD = "channel";
        private static final String SETI_ID_FIELD = "setiId";
        private static final String DATA_FIELD = "data";

        private SetiMessage(String toUser, String toChannel, Object data)
        {
            put(USER_ID_FIELD, toUser);
            put(CHANNEL_FIELD, toChannel);
            put(SETI_ID_FIELD, _setiId);
            put(DATA_FIELD, data);
        }
    }

    private class SetiPresence extends HashMap<String, Object>
    {
        private static final String USER_ID_FIELD = "uid";
        private static final String SETI_ID_FIELD = "setiId";
        private static final String PRESENCE_FIELD = "presence";

        private SetiPresence(String userId, boolean on)
        {
            put(USER_ID_FIELD, userId);
            put(SETI_ID_FIELD, _setiId);
            put(PRESENCE_FIELD, on);
        }
    }
}
