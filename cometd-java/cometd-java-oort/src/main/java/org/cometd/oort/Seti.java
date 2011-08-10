/*
 * Copyright (c) 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.oort;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
 * in this case, just searches for a user logged onto a comet in an Oort cloud.</p>
 * <p>Seti allows an application to maintain a mapping from userId (any application
 * identifier such as user names or database IDs that represent users) to
 * server sessions using the {@link #associate(String, ServerSession)} and
 * {@link #disassociate(String, ServerSession)} methods.</p>
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

    private final Map<String, Set<Location>> _uid2Location = new HashMap<String, Set<Location>>();
    private final Logger _logger;
    private final Oort _oort;
    private final String _setiId;
    private final LocalSession _session;

    public Seti(Oort oort)
    {
        _logger = Log.getLogger(getClass().getName() + "-" + oort.getURL());
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

    /**
     * <p>Associates the given userId to the given session.</p>
     * <p>If it is the first association for this userId, broadcasts this information
     * on the Oort cloud, so that other comets will know that the given userId is on this comet.</p>
     *
     * @param userId  the user identifier to associate
     * @param session the session to map the userId to
     * @return true if the session has been associated, false if it was already associated
     * @see #isAssociated(String)
     * @see #disassociate(String, ServerSession)
     */
    public boolean associate(final String userId, final ServerSession session)
    {
        if (session == null)
            throw new NullPointerException();

        LocalLocation location = new LocalLocation(userId, session);
        boolean wasAssociated = isAssociated(userId);
        boolean added = associate(userId, location);

        if (added)
        {
            session.addListener(location);
            _logger.debug("Associated session {} to user {}", session, userId);
            if (!wasAssociated)
            {
                _logger.debug("Broadcasting presence addition for user {}", userId);
                // Let everyone in the cluster know that this session is here
                _oort.getBayeuxServer().getChannel(SETI_ALL_CHANNEL).publish(_session, new SetiPresence(userId, true), null);
            }
        }

        return added;
    }

    protected boolean associate(String userId, Location location)
    {
        synchronized (_uid2Location)
        {
            Set<Location> locations = _uid2Location.get(userId);
            if (locations == null)
            {
                locations = new HashSet<Location>();
                _uid2Location.put(userId, locations);
            }
            boolean result = locations.add(location);
            _logger.debug("Associations {}", _uid2Location);
            return result;
        }
    }

    /**
     * @param userId the user identifier to test for association
     * @return whether the given userId has been associated via {@link #associate(String, ServerSession)}
     * @see #associate(String, ServerSession)
     */
    public boolean isAssociated(String userId)
    {
        synchronized (_uid2Location)
        {
            Set<Location> locations = _uid2Location.get(userId);
            if (locations == null)
                return false;
            for (Location location : locations)
            {
                if (location instanceof LocalLocation)
                    return true;
            }
            return false;
        }
    }

    /**
     * <p>Disassociates the given userId from the given session.</p>
     * <p>If this is the last disassociation for this userId, broadcasts this information
     * on the Oort cloud, so that other comets will know that the given userId no longer is on this comet.</p>
     *
     * @param userId the user identifier to disassociate
     * @param session the session mapped to the userId
     * @return true if the session has been disassociated, false if it was not associated
     * @see #associate(String, ServerSession)
     */
    public boolean disassociate(final String userId, ServerSession session)
    {
        LocalLocation location = new LocalLocation(userId, session);
        boolean removed = disassociate(userId, location);
        if (removed)
            _logger.debug("Disassociated session {} from user {}", session, userId);

        // Seti is stopped before BayeuxServer, but it may happen that RemoveListeners
        // call Seti when BayeuxServer is stopping, and they will find that Seti is already stopped.
        // Do not do any action in this case, because exceptions are thrown if the action is
        // attempted (as _session is already disconnected).
        // Also, we only broadcast the presence message if no associations are left for the user,
        // because remote comets are not aware of multiple associations.
        // Consider the case where the same user is associated twice to a comet, and then only
        // one association is disassociated. The other comets do not know that the comet had multiple
        // associations, and if a presence message is sent, the remote comets will wrongly think
        // that the user is gone, while in reality it is still associated with the remaining association.
        if (_session.isConnected() && !isAssociated(userId))
        {
            _logger.debug("Broadcasting presence removal for user {}", userId);
            // Let everyone in the cluster know that this session is not here anymore
            _oort.getBayeuxServer().getChannel(SETI_ALL_CHANNEL).publish(_session, new SetiPresence(userId, false), null);
        }

        return removed;
    }

    protected boolean disassociate(String userId, Location location)
    {
        synchronized (_uid2Location)
        {
            boolean result = false;
            Set<Location> locations = _uid2Location.get(userId);
            if (locations != null)
            {
                result = locations.remove(location);
                if (locations.isEmpty())
                    _uid2Location.remove(userId);
            }
            _logger.debug("Associations {}", _uid2Location);
            return result;
        }
    }

    /**
     * <p>Sends a message to the given userId in the Oort cloud.</p>
     *
     * @param toUserId  the userId to send the message to
     * @param toChannel the channel to send the message to
     * @param data      the content of the message
     * @see #sendMessage(Collection, String, Object)
     */
    public void sendMessage(final String toUserId, final String toChannel, final Object data)
    {
        sendMessage(Collections.singleton(toUserId), toChannel, data);
    }

    /**
     * <p>Sends a message to multiple userIds in the Oort cloud.</p>
     *
     * @param toUserIds the userIds to send the message to
     * @param toChannel the channel to send the message to
     * @param data      the content of the message
     */
    public void sendMessage(final Collection<String> toUserIds, final String toChannel, final Object data)
    {
        for (String toUserId : toUserIds)
        {
            Set<Location> copy = new HashSet<Location>();
            synchronized (_uid2Location)
            {
                Set<Location> locations = _uid2Location.get(toUserId);
                if (locations == null)
                    copy.add(new SetiLocation(toUserId, SETI_ALL_CHANNEL));
                else
                    copy.addAll(locations);
            }

            _logger.debug("Sending message to locations {}", copy);
            for (Location location : copy)
                location.send(toUserId, toChannel, data);
        }
    }

    /**
     * <p>Receives messages directly from other Setis in the cloud, containing
     * messages to be delivered to sessions connected to this comet.</p>
     *
     * @param message the message to deliver to a session connected to this comet
     */
    protected void receiveDirect(Message message)
    {
        _logger.debug("Received direct message {}", message);
        receiveMessage(message);
    }

    /**
     * <p>Receives messages broadcasted by other Setis in the cloud.</p>
     * <p>Broadcasted messages may be presence messages, where another Seti advertises
     * an association, or fallback messages. <br />
     * Fallback messages are messages that were sent to a particular Seti because the
     * sender thought the target userId was there, but the receiving Seti does not know
     * that userId anymore (for example, it just disconnected); in this case, the receiving
     * Seti broadcasts the message to the whole cloud, in the hope that the user can be
     * found in some other comet of the cloud.</p>
     *
     * @param message the message to possibly deliver to a session connected to this comet
     */
    protected void receiveBroadcast(Message message)
    {
        Map<String, Object> data = message.getDataAsMap();
        Boolean presence = (Boolean)data.get(SetiPresence.PRESENCE_FIELD);
        if (presence != null)
            receivePresence(message);
        else
            receiveMessage(message);
    }

    /**
     * <p>Receives a presence message.</p>
     *
     * @param message the presence message received
     */
    protected void receivePresence(Message message)
    {
        Map<String, Object> data = message.getDataAsMap();
        String setiId = (String)data.get(SetiPresence.SETI_ID_FIELD);
        if (_setiId.equals(setiId))
            return;

        _logger.debug("Received presence message {}", message);

        String userId = (String)data.get(SetiPresence.USER_ID_FIELD);
        SetiLocation location = new SetiLocation(userId, "/seti/" + setiId);
        boolean presence = (Boolean)data.get(SetiPresence.PRESENCE_FIELD);
        if (presence)
            associate(userId, location);
        else
            disassociate(userId, location);
    }

    /**
     * <p>Receives a seti message.</p>
     *
     * @param message the seti message received
     */
    protected void receiveMessage(Message message)
    {
        Map<String, Object> messageData = message.getDataAsMap();
        String userId = (String)messageData.get(SetiMessage.USER_ID_FIELD);
        String channel = (String)messageData.get(SetiMessage.CHANNEL_FIELD);
        Object data = messageData.get(SetiMessage.DATA_FIELD);

        Set<Location> copy = new HashSet<Location>();
        synchronized (_uid2Location)
        {
            Set<Location> locations = _uid2Location.get(userId);
            if (locations != null)
            {
                // Consider cometA, cometB and cometC and a user that is associated
                // in both cometA and cometB. When cometC sends a message to the user,
                // it knows that the user is in both cometA and cometB (thanks to presence
                // messages) and will send a message to both cometA and cometB.
                // But cometA also knows from presence messages that the user is also in
                // cometB and should not forward the message arriving from cometC to cometB
                // since cometC will take care of sending to cometB.
                // Hence, we forward the message only locally
                for (Location location : locations)
                {
                    if (location instanceof LocalLocation)
                        copy.add(location);
                }
            }
        }

        _logger.debug("Received message {} for locations {}", message, copy);
        for (Location location : copy)
            location.receive(userId, channel, data);
    }

    /**
     * <p>The representation of where a user is.</p>
     */
    protected interface Location
    {
        public void send(String toUser, String toChannel, Object data);

        public void receive(String toUser, String toChannel, Object data);

        public int hashCode();

        public boolean equals(Object obj);
    }

    /**
     * <p>A Location that represent a user connected to a local comet.</p>
     */
    protected class LocalLocation implements Location, ServerSession.RemoveListener
    {
        private final String _userId;
        private final ServerSession _session;

        protected LocalLocation(String userId, ServerSession session)
        {
            _userId = userId;
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

        public void removed(ServerSession session, boolean timeout)
        {
            disassociate(_userId, session);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!(obj instanceof LocalLocation))
                return false;
            LocalLocation that = (LocalLocation)obj;
            return _userId.equals(that._userId) && _session.getId().equals(that._session.getId());
        }

        @Override
        public int hashCode()
        {
            return 31 * _userId.hashCode() + _session.getId().hashCode();
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "[" + _session + "]";
        }
    }

    /**
     * <p>A Location that represent a user connected to a remote comet.</p>
     */
    protected class SetiLocation implements Location
    {
        private final String _userId;
        private final String _setiId;

        protected SetiLocation(String userId, String channelId)
        {
            _userId = userId;
            _setiId = channelId;
        }

        public void send(String toUser, String toChannel, Object data)
        {
            _session.getChannel(_setiId).publish(new SetiMessage(toUser, toChannel, data));
        }

        public void receive(String toUser, String toChannel, Object data)
        {
            // A message has been sent to this comet because the sender thought
            // the user was in this comet. If it were, we would have found a
            // LocalLocation, but instead found this SetiLocation.
            // Therefore, the user must have moved to this seti location, and
            // we forward the message.
            send(toUser, toChannel, data);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!(obj instanceof SetiLocation))
                return false;
            SetiLocation that = (SetiLocation)obj;
            return _userId.equals(that._userId) && _setiId.equals(that._setiId);
        }

        @Override
        public int hashCode()
        {
            return 31 * _userId.hashCode() + _setiId.hashCode();
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "[" + _setiId + "]";
        }
    }

    private class SetiMessage extends HashMap<String, Object>
    {
        private static final String USER_ID_FIELD = "userId";
        private static final String CHANNEL_FIELD = "channel";
        private static final String SETI_ID_FIELD = "setiId";
        private static final String DATA_FIELD = "data";

        private SetiMessage(String toUser, String toChannel, Object data)
        {
            super(4);
            put(USER_ID_FIELD, toUser);
            put(CHANNEL_FIELD, toChannel);
            put(SETI_ID_FIELD, _setiId);
            put(DATA_FIELD, data);
        }
    }

    private class SetiPresence extends HashMap<String, Object>
    {
        private static final String USER_ID_FIELD = "userId";
        private static final String SETI_ID_FIELD = "setiId";
        private static final String PRESENCE_FIELD = "presence";

        private SetiPresence(String userId, boolean on)
        {
            super(3);
            put(USER_ID_FIELD, userId);
            put(SETI_ID_FIELD, _setiId);
            put(PRESENCE_FIELD, on);
        }
    }
}
