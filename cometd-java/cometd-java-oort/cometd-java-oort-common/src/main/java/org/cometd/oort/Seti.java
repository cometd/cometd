/*
 * Copyright (c) 2008-2022 the original author or authors.
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

import java.io.IOException;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.EventListener;
import java.util.EventObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 */
@ManagedObject("CometD cloud peer discovery component")
public class Seti extends AbstractLifeCycle implements Dumpable {
    public static final String SETI_ATTRIBUTE = Seti.class.getName();
    private static final String SETI_ALL_CHANNEL = "/seti/all";
    private static final List<String> PROTECTED_CHANNELS = Arrays.asList("/seti/**", "/seti/*");

    private final AutoLock lock = new AutoLock();
    private final Map<String, Set<Location>> _uid2Location = new HashMap<>();
    private final List<PresenceListener> _presenceListeners = new CopyOnWriteArrayList<>();
    private final Oort.CometListener _cometListener = new CometListener();
    private final ServerChannel.SubscriptionListener _initialStateListener = new InitialStateListener();
    private final BayeuxServer.SubscriptionListener _allChannelsFilter = new AllChannelsFilter();
    private final Authorizer _authorizer = new SetiAuthorizer();
    private final Oort _oort;
    private final String _setiId;
    private final Logger _logger;
    private final LocalSession _session;

    public Seti(Oort oort) {
        _oort = oort;
        _setiId = generateSetiId(oort.getURL());
        _logger = LoggerFactory.getLogger(getClass().getName() + "." + _setiId);
        _session = oort.getBayeuxServer().newLocalSession(_setiId);
    }

    @ManagedAttribute(value = "The Oort of this Seti", readonly = true)
    public Oort getOort() {
        return _oort;
    }

    @ManagedAttribute(value = "The unique ID of this Seti", readonly = true)
    public String getId() {
        return _setiId;
    }

    @Override
    protected void doStart() {
        BayeuxServer bayeux = _oort.getBayeuxServer();

        bayeux.addListener(_allChannelsFilter);

        _session.handshake();

        protectSetiChannels(bayeux);

        ServerChannel setiAllChannel = bayeux.createChannelIfAbsent(SETI_ALL_CHANNEL).getReference();
        setiAllChannel.addListener(_initialStateListener);
        _session.getChannel(SETI_ALL_CHANNEL).subscribe((channel, message) -> receiveBroadcast(message));
        _oort.observeChannel(SETI_ALL_CHANNEL);

        String setiChannelName = generateSetiChannel(_setiId);
        _session.getChannel(setiChannelName).subscribe((channel, message) -> receiveDirect(message));
        _oort.observeChannel(setiChannelName);

        _oort.addExactSubscriptions(SETI_ALL_CHANNEL, setiChannelName);

        _oort.addCometListener(_cometListener);

        if (_logger.isDebugEnabled()) {
            _logger.debug("{} started", this);
        }
    }

    @Override
    protected void doStop() {
        BayeuxServer bayeux = _oort.getBayeuxServer();

        removeAssociationsAndPresences();
        _presenceListeners.clear();

        _oort.removeCometListener(_cometListener);

        String setiChannelName = generateSetiChannel(_setiId);
        _oort.removeExactSubscriptions(SETI_ALL_CHANNEL, setiChannelName);

        _oort.deobserveChannel(setiChannelName);

        _oort.deobserveChannel(SETI_ALL_CHANNEL);
        ServerChannel setiAllChannel = bayeux.getChannel(SETI_ALL_CHANNEL);
        if (setiAllChannel != null) {
            setiAllChannel.removeListener(_initialStateListener);
        }

        unprotectSetiChannels(bayeux);

        _session.disconnect();

        bayeux.removeListener(_allChannelsFilter);
    }

    protected void protectSetiChannels(BayeuxServer bayeux) {
        PROTECTED_CHANNELS.forEach(name -> bayeux.createChannelIfAbsent(name, channel -> channel.addAuthorizer(_authorizer)));
    }

    protected void unprotectSetiChannels(BayeuxServer bayeux) {
        PROTECTED_CHANNELS.forEach(name -> {
            ServerChannel channel = bayeux.getChannel(name);
            if (channel != null) {
                channel.removeAuthorizer(_authorizer);
            }
        });
    }

    protected String generateSetiId(String oortURL) {
        return Oort.replacePunctuation(oortURL, '_');
    }

    protected String generateSetiChannel(String setiId) {
        return "/seti/" + setiId;
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
    public boolean associate(String userId, ServerSession session) {
        if (session == null) {
            throw new NullPointerException();
        }

        LocalLocation location = new LocalLocation(userId, session);
        boolean wasAssociated = isAssociated(userId);
        boolean added = associate(userId, location);

        if (added) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Associated {} to user {}", session, userId);
            }

            session.addListener(location);
            session.setAttribute(LocalLocation.class.getName(), location);

            if (!wasAssociated) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Broadcasting association addition for user {}", userId);
                }
                // Let everyone in the cluster know that this session is here
                _session.getChannel(SETI_ALL_CHANNEL).publish(new SetiPresence(true, userId));
            }
        }

        return added;
    }

    protected boolean associate(String userId, Location location) {
        if (!isRunning()) {
            return false;
        }

        try (AutoLock ignored = lock.lock()) {
            Set<Location> locations = _uid2Location.computeIfAbsent(userId, k -> new HashSet<>());
            boolean result = locations.add(location);
            if (_logger.isDebugEnabled()) {
                _logger.debug("Associations: {}", _uid2Location.size());
            }
            // Logging below can generate hugely long lines.
            if (_logger.isTraceEnabled()) {
                _logger.trace("Associations: {}", _uid2Location);
            }
            return result;
        }
    }

    private boolean associateRemote(String userId, SetiLocation location) {
        // There is a possibility that a remote Seti sends an association,
        // but immediately afterwards crashes.
        // This Seti may process the crash _before_ the association, leaving
        // this Seti with an association to a crashed Seti.
        // Here we check if, after the association, the other node is
        // still connected. If not, we disassociate. If so, and the other
        // node crashes just afterwards, the "comet left" event will take
        // care of disassociating the remote users.
        boolean associated = associate(userId, location);
        String oortURL = location._oortURL;
        if (associated && !_oort.isCometConnected(oortURL)) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Disassociating {} since comet {} is not connected", userId, oortURL);
            }
            disassociate(userId, location);
            return false;
        }
        return associated;
    }

    /**
     * @param userId the user identifier to test for association
     * @return whether the given userId has been associated via {@link #associate(String, ServerSession)}
     * @see #associate(String, ServerSession)
     * @see #isPresent(String)
     * @see #getAssociationCount(String)
     */
    @ManagedOperation(value = "Whether the given userId is associated locally", impact = "INFO")
    public boolean isAssociated(@Name(value = "userId", description = "The userId to test for local association") String userId) {
        try (AutoLock ignored = lock.lock()) {
            Set<Location> locations = _uid2Location.get(userId);
            if (locations == null) {
                return false;
            }
            for (Location location : locations) {
                if (location instanceof LocalLocation) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * @param userId the user identifier to test for association count
     * @return the number of local associations
     * @see #isAssociated(String)
     * @see #getPresenceCount(String)
     */
    @ManagedOperation(value = "The number of local associations for the given userId", impact = "INFO")
    public int getAssociationCount(@Name(value = "userId", description = "The userId to test for local association count") String userId) {
        try (AutoLock ignored = lock.lock()) {
            Set<Location> locations = _uid2Location.get(userId);
            if (locations == null) {
                return 0;
            }
            int result = 0;
            for (Location location : locations) {
                if (location instanceof LocalLocation) {
                    ++result;
                }
            }
            return result;
        }
    }

    /**
     * @param userId the user identifier to test for presence
     * @return whether the given userId is present on the cloud (and therefore has been associated
     * either locally or remotely)
     * @see #isAssociated(String)
     * @see #getPresenceCount(String)
     */
    @ManagedOperation(value = "Whether the given userId is present in the cloud", impact = "INFO")
    public boolean isPresent(@Name(value = "userId", description = "The userId to test for presence in the cloud") String userId) {
        try (AutoLock ignored = lock.lock()) {
            Set<Location> locations = _uid2Location.get(userId);
            return locations != null;
        }
    }

    /**
     * @param userId the user identifier to test for presence count
     * @return the number of associations (local or remote) on the cloud
     * @see #isPresent(String)
     * @see #getAssociationCount(String)
     */
    @ManagedOperation(value = "The number of local and remote associations for the given userId", impact = "INFO")
    public int getPresenceCount(@Name(value = "userId", description = "The userId to test for presence count") String userId) {
        try (AutoLock ignored = lock.lock()) {
            Set<Location> locations = _uid2Location.get(userId);
            return locations == null ? 0 : locations.size();
        }
    }

    /**
     * <p>Disassociates the given userId from the given session.</p>
     * <p>If this is the last disassociation for this userId, broadcasts this information
     * on the Oort cloud, so that other comets will know that the given userId no longer is on this comet.</p>
     *
     * @param userId  the user identifier to disassociate
     * @param session the session mapped to the userId
     * @return true if the session has been disassociated, false if it was not associated
     * @see #associate(String, ServerSession)
     */
    public boolean disassociate(String userId, ServerSession session) {
        LocalLocation location = new LocalLocation(userId, session);
        boolean removed = disassociate(userId, location);

        if (removed) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Disassociated {} from user {}", session, userId);
            }

            location = (LocalLocation)session.removeAttribute(LocalLocation.class.getName());
            session.removeListener(location);

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
            if (_session.isConnected() && !isAssociated(userId)) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Broadcasting association removal for user {}", userId);
                }
                // Let everyone in the cluster know that this session is not here anymore
                _session.getChannel(SETI_ALL_CHANNEL).publish(new SetiPresence(false, userId));
            }
        }

        return removed;
    }

    /**
     * <p>Disassociates the given userId from all sessions.</p>
     * <p>If this user had sessions on this comet, broadcast this information
     * on the Oort cloud, so that other comets will know that the given userId no longer is on this comet.</p>
     *
     * @param userId the user identifier to disassociate
     * @return set of sessions that were disassociated
     * @see #associate(String, ServerSession)
     */
    public Set<ServerSession> disassociate(String userId) {
        Set<LocalLocation> localLocations = new HashSet<>();
        try (AutoLock ignored = lock.lock()) {
            Set<Location> userLocations = _uid2Location.get(userId);
            if (userLocations != null) {
                for (Location location : userLocations) {
                    if (location instanceof LocalLocation) {
                        localLocations.add((LocalLocation)location);
                    }
                }
            }
        }

        Set<ServerSession> removedUserSessions = new HashSet<>();
        for (LocalLocation location : localLocations) {
            ServerSession session = location._session;
            boolean removed = disassociate(userId, session);
            if (removed) {
                removedUserSessions.add(session);
            }
        }

        return removedUserSessions;
    }

    protected boolean disassociate(String userId, Location location) {
        try (AutoLock ignored = lock.lock()) {
            boolean result = false;
            Set<Location> locations = _uid2Location.get(userId);
            if (locations != null) {
                result = locations.remove(location);
                if (locations.isEmpty()) {
                    _uid2Location.remove(userId);
                }
            }
            if (_logger.isDebugEnabled()) {
                _logger.debug("Associations: {}", _uid2Location.size());
            }
            // Logging below can generate hugely long lines.
            if (_logger.isTraceEnabled()) {
                _logger.trace("Associations: {}", _uid2Location);
            }
            return result;
        }
    }

    protected void removeAssociationsAndPresences() {
        Set<String> userIds = new HashSet<>();
        try (AutoLock ignored = lock.lock()) {
            getAssociatedUserIds(userIds);
            _uid2Location.clear();
        }
        if (_logger.isDebugEnabled()) {
            _logger.debug("Broadcasting association removal for users {}", userIds);
        }
        SetiPresence presence = new SetiPresence(Set.of());
        presence.put(SetiPresence.ALIVE_FIELD, false);
        _session.getChannel(SETI_ALL_CHANNEL).publish(presence);
    }

    protected void removePresences(String oortURL) {
        Set<String> userIds = removeRemotePresences(oortURL);
        if (_logger.isDebugEnabled()) {
            _logger.debug("Removing presences of comet {} for users {}", oortURL, userIds);
        }
        for (String userId : userIds) {
            notifyPresenceRemoved(oortURL, userId);
        }
    }

    private Set<String> removeRemotePresences(String oortURL) {
        Set<String> userIds = new HashSet<>();
        try (AutoLock ignored = lock.lock()) {
            Iterator<Map.Entry<String, Set<Location>>> entries = _uid2Location.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, Set<Location>> entry = entries.next();
                Set<Location> userLocations = entry.getValue();
                Iterator<Location> iterator = userLocations.iterator();
                while (iterator.hasNext()) {
                    Location location = iterator.next();
                    if (location instanceof SetiLocation) {
                        if (oortURL.equals(((SetiLocation)location)._oortURL)) {
                            iterator.remove();
                            userIds.add(entry.getKey());
                            break;
                        }
                    }
                }
                if (userLocations.isEmpty()) {
                    entries.remove();
                }
            }
        }
        return userIds;
    }

    /**
     * @return the set of {@code userId}s known to this Seti, both local and remote
     */
    @ManagedAttribute(value = "The set of userIds known to this Seti", readonly = true)
    public Set<String> getUserIds() {
        try (AutoLock ignored = lock.lock()) {
            return Set.copyOf(_uid2Location.keySet());
        }
    }

    /**
     * @return the set of {@code userId}s associated via {@link #associate(String, ServerSession)}
     */
    public Set<String> getAssociatedUserIds() {
        Set<String> result = new HashSet<>();
        getAssociatedUserIds(result);
        return result;
    }

    private void getAssociatedUserIds(Set<String> result) {
        try (AutoLock ignored = lock.lock()) {
            for (Map.Entry<String, Set<Location>> entry : _uid2Location.entrySet()) {
                for (Location location : entry.getValue()) {
                    if (location instanceof LocalLocation) {
                        result.add(entry.getKey());
                        break;
                    }
                }
            }
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
    public void sendMessage(String toUserId, String toChannel, Object data) {
        sendMessage(Set.of(toUserId), toChannel, data);
    }

    /**
     * <p>Sends a message to multiple userIds in the Oort cloud.</p>
     *
     * @param toUserIds the userIds to send the message to
     * @param toChannel the channel to send the message to
     * @param data      the content of the message
     */
    public void sendMessage(Collection<String> toUserIds, String toChannel, Object data) {
        for (String toUserId : toUserIds) {
            Set<Location> copy = new HashSet<>();
            try (AutoLock ignored = lock.lock()) {
                Set<Location> locations = _uid2Location.get(toUserId);
                if (locations == null) {
                    copy.add(new SetiLocation(toUserId, null));
                } else {
                    copy.addAll(locations);
                }
            }

            if (_logger.isDebugEnabled()) {
                _logger.debug("Sending message to locations {}", copy);
            }
            for (Location location : copy) {
                location.send(toUserId, toChannel, data);
            }
        }
    }

    /**
     * <p>Receives messages directly from other Setis in the cloud, containing
     * messages to be delivered to sessions connected to this comet.</p>
     *
     * @param message the message to deliver to a session connected to this comet
     */
    protected void receiveDirect(Message message) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Received direct message {}", message);
        }
        Map<String, Object> data = message.getDataAsMap();
        Boolean presence = (Boolean)data.get(SetiPresence.PRESENCE_FIELD);
        if (presence != null) {
            receivePresence(data);
        } else {
            receiveMessage(data);
        }
    }

    /**
     * <p>Receives messages broadcast by other Setis in the cloud.</p>
     * <p>Broadcast messages may be presence messages, where another Seti advertises
     * an association, or fallback messages.
     * Fallback messages are messages that were sent to a particular Seti because the
     * sender thought the target userId was there, but the receiving Seti does not know
     * that userId anymore (for example, it just disconnected); in this case, the receiving
     * Seti broadcasts the message to the whole cloud, in the hope that the user can be
     * found in some other comet of the cloud.</p>
     *
     * @param message the message to possibly deliver to a session connected to this comet
     */
    protected void receiveBroadcast(Message message) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Received broadcast message {}", message);
        }
        Map<String, Object> data = message.getDataAsMap();
        Boolean presence = (Boolean)data.get(SetiPresence.PRESENCE_FIELD);
        if (presence != null) {
            receivePresence(data);
        } else {
            receiveMessage(data);
        }
    }

    /**
     * <p>Receives a presence message.</p>
     *
     * @param presence the presence message received
     */
    protected void receivePresence(Map<String, Object> presence) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Received presence message {}", presence);
        }
        String oortURL = (String)presence.get(SetiPresence.OORT_URL_FIELD);
        if (_setiId.equals(generateSetiId(oortURL))) {
            receiveLocalPresence(presence);
        } else {
            receiveRemotePresence(presence);
        }
    }

    protected void receiveLocalPresence(Map<String, Object> presence) {
        String oortURL = (String)presence.get(SetiPresence.OORT_URL_FIELD);
        boolean present = (Boolean)presence.get(SetiPresence.PRESENCE_FIELD);
        Set<String> userIds = convertPresenceUsers(presence);
        if (_logger.isDebugEnabled()) {
            _logger.debug("Notifying presence listeners {}", presence);
        }
        for (String userId : userIds) {
            if (present) {
                notifyPresenceAdded(oortURL, userId);
            } else {
                notifyPresenceRemoved(oortURL, userId);
            }
        }
    }

    protected void receiveRemotePresence(Map<String, Object> presence) {
        String oortURL = (String)presence.get(SetiPresence.OORT_URL_FIELD);
        boolean present = (Boolean)presence.get(SetiPresence.PRESENCE_FIELD);
        boolean replace = presence.get(SetiPresence.REPLACE_FIELD) == Boolean.TRUE;
        Set<String> userIds = convertPresenceUsers(presence);

        if (_logger.isDebugEnabled()) {
            _logger.debug("Received remote presence message from comet {} for {}", oortURL, userIds);
        }

        Set<String> added = new HashSet<>();
        Set<String> removed = new HashSet<>();
        try (AutoLock ignored = lock.lock()) {
            if (replace) {
                removed.addAll(removeRemotePresences(oortURL));
            }
            for (String userId : userIds) {
                SetiLocation location = new SetiLocation(userId, oortURL);
                if (present) {
                    if (associateRemote(userId, location)) {
                        added.add(userId);
                    }
                } else {
                    if (disassociate(userId, location)) {
                        removed.add(userId);
                    }
                }
            }
        }

        Set<String> toRemove = new HashSet<>(removed);
        toRemove.removeAll(added);
        for (String userId : toRemove) {
            notifyPresenceRemoved(oortURL, userId);
        }
        added.removeAll(removed);
        for (String userId : added) {
            notifyPresenceAdded(oortURL, userId);
        }

        if (presence.get(SetiPresence.ALIVE_FIELD) == Boolean.TRUE) {
            // Message sent on startup by the remote Seti, push our associations
            OortComet oortComet = _oort.findComet(oortURL);
            if (oortComet != null) {
                Set<String> associatedUserIds = getAssociatedUserIds();
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Pushing associated users {} to comet {}", associatedUserIds, oortURL);
                }
                ClientSessionChannel channel = oortComet.getChannel(generateSetiChannel(generateSetiId(oortURL)));
                channel.publish(new SetiPresence(associatedUserIds));
            }
        }
    }

    public void addPresenceListener(PresenceListener listener) {
        _presenceListeners.add(listener);
    }

    public void removePresenceListener(PresenceListener listener) {
        _presenceListeners.remove(listener);
    }

    public void removePresenceListeners() {
        _presenceListeners.clear();
    }

    private void notifyPresenceAdded(String oortURL, String userId) {
        PresenceListener.Event event = new PresenceListener.Event(this, userId, oortURL);
        for (PresenceListener listener : _presenceListeners) {
            try {
                listener.presenceAdded(event);
            } catch (Throwable x) {
                _logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyPresenceRemoved(String oortURL, String userId) {
        PresenceListener.Event event = new PresenceListener.Event(this, userId, oortURL);
        for (PresenceListener listener : _presenceListeners) {
            try {
                listener.presenceRemoved(event);
            } catch (Throwable x) {
                _logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    /**
     * <p>Receives a seti message.</p>
     *
     * @param message the seti message received
     */
    protected void receiveMessage(Map<String, Object> message) {
        String userId = (String)message.get(SetiMessage.USER_ID_FIELD);
        String channel = (String)message.get(SetiMessage.CHANNEL_FIELD);
        Object data = message.get(SetiMessage.DATA_FIELD);

        Set<Location> copy = new HashSet<>();
        try (AutoLock ignored = lock.lock()) {
            Set<Location> locations = _uid2Location.get(userId);
            if (locations != null) {
                // Consider cometA, cometB and cometC and a user that is associated
                // in both cometA and cometB. When cometC sends a message to the user,
                // it knows that the user is in both cometA and cometB (thanks to presence
                // messages) and will send a message to both cometA and cometB.
                // But cometA also knows from presence messages that the user is also in
                // cometB and should not forward the message arriving from cometC to cometB
                // since cometC will take care of sending to cometB.
                // Hence, we forward the message only locally
                for (Location location : locations) {
                    if (location instanceof LocalLocation) {
                        copy.add(location);
                    }
                }
            }
        }

        if (_logger.isDebugEnabled()) {
            _logger.debug("Received message {} for locations {}", message, copy);
        }
        for (Location location : copy) {
            location.receive(userId, channel, data);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> convertPresenceUsers(Map<String, Object> presence) {
        Object value = presence.get(SetiPresence.USER_IDS_FIELD);
        if (value instanceof Set) {
            return (Set<String>)value;
        }
        if (value instanceof Collection) {
            return Set.copyOf((Collection<? extends String>)value);
        }
        if (value.getClass().isArray()) {
            Set<String> result = new HashSet<>();
            for (Object item : (Object[])value) {
                result.add(item.toString());
            }
            return result;
        }
        throw new IllegalArgumentException();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException {
        boolean detailed = ((BayeuxServerImpl)getOort().getBayeuxServer()).isDetailedDump();
        if (detailed) {
            List<Map.Entry<String, ? extends Set<Location>>> locations;
            try (AutoLock ignored = lock.lock()) {
                locations = new TreeMap<>(_uid2Location).entrySet().stream()
                        .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), new HashSet<>(entry.getValue())))
                        .collect(Collectors.toList());
            }
            Dumpable.dumpObjects(out, indent, this, new DumpableCollection("locations", locations));
        } else {
            int size;
            try (AutoLock ignored = lock.lock()) {
                size = _uid2Location.size();
            }
            Dumpable.dumpObjects(out, indent, this, "locations size=" + size);
        }
    }

    @Override
    public String toString() {
        return String.format("%s[%s]", getClass().getName(), getId());
    }

    /**
     * <p>The representation of where a user is.</p>
     */
    protected interface Location {
        public void send(String toUser, String toChannel, Object data);

        public void receive(String toUser, String toChannel, Object data);

        @Override
        public int hashCode();

        @Override
        public boolean equals(Object obj);
    }

    /**
     * <p>A Location that represent a user connected to a local comet.</p>
     */
    protected class LocalLocation implements Location, ServerSession.RemovedListener {
        private final String _userId;
        private final ServerSession _session;

        protected LocalLocation(String userId, ServerSession session) {
            _userId = userId;
            _session = session;
        }

        @Override
        public void send(String toUser, String toChannel, Object data) {
            _session.deliver(Seti.this._session, toChannel, data, Promise.noop());
        }

        @Override
        public void receive(String toUser, String toChannel, Object data) {
            send(toUser, toChannel, data);
        }

        @Override
        public void removed(ServerSession session, ServerMessage message, boolean timeout) {
            disassociate(_userId, session);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof LocalLocation that) {
                return _userId.equals(that._userId) && _session.getId().equals(that._session.getId());
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(_userId, _session.getId());
        }

        @Override
        public String toString() {
            return String.format("%s[%s]", getClass().getSimpleName(), _session);
        }
    }

    /**
     * <p>A Location that represent a user connected to a remote comet.</p>
     */
    protected class SetiLocation implements Location {
        private final String _userId;
        private final String _oortURL;
        private final String _setiChannel;

        protected SetiLocation(String userId, String oortURL) {
            _userId = userId;
            _oortURL = oortURL;
            _setiChannel = oortURL == null ? SETI_ALL_CHANNEL : generateSetiChannel(generateSetiId(oortURL));
        }

        @Override
        public void send(String toUser, String toChannel, Object data) {
            _session.getChannel(_setiChannel).publish(new SetiMessage(toUser, toChannel, data));
        }

        @Override
        public void receive(String toUser, String toChannel, Object data) {
            // A message has been sent to this comet because the sender thought
            // the user was in this comet. If it were, we would have found a
            // LocalLocation, but instead found this SetiLocation.
            // Therefore, the user must have moved to this seti location, and
            // we forward the message.
            send(toUser, toChannel, data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof SetiLocation that) {
                return _userId.equals(that._userId) && _setiChannel.equals(that._setiChannel);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(_userId, _setiChannel);
        }

        @Override
        public String toString() {
            return String.format("%s[%s]", getClass().getSimpleName(), _setiChannel);
        }
    }

    private class SetiMessage extends HashMap<String, Object> {
        private static final String USER_ID_FIELD = "userId";
        private static final String CHANNEL_FIELD = "channel";
        private static final String SETI_ID_FIELD = "setiId";
        private static final String DATA_FIELD = "data";

        private SetiMessage(String toUser, String toChannel, Object data) {
            super(4);
            put(USER_ID_FIELD, toUser);
            put(CHANNEL_FIELD, toChannel);
            put(SETI_ID_FIELD, _setiId);
            put(DATA_FIELD, data);
        }
    }

    private class SetiPresence extends HashMap<String, Object> {
        private static final String USER_IDS_FIELD = "userIds";
        private static final String OORT_URL_FIELD = "oortURL";
        private static final String ALIVE_FIELD = "alive";
        private static final String PRESENCE_FIELD = "presence";
        private static final String REPLACE_FIELD = "replace";

        private SetiPresence(boolean present, String userId) {
            this(present, Set.of(userId), false);
        }

        private SetiPresence(Set<String> userIds) {
            this(true, userIds, true);
        }

        private SetiPresence(boolean present, Set<String> userIds, boolean replace) {
            super(4);
            put(USER_IDS_FIELD, userIds);
            put(OORT_URL_FIELD, _oort.getURL());
            put(PRESENCE_FIELD, present);
            put(REPLACE_FIELD, replace);
        }
    }

    /**
     * Listener interface that gets notified of remote Seti presence events.
     */
    public interface PresenceListener extends EventListener {
        /**
         * Callback method invoked when a presence is added to a remote Seti
         *
         * @param event the presence event
         */
        public default void presenceAdded(Event event) {
        }

        /**
         * Callback method invoked when a presence is removed from a remote Seti
         *
         * @param event the presence event
         */
        public default void presenceRemoved(Event event) {
        }

        /**
         * Seti presence event object, delivered to {@link PresenceListener} methods.
         */
        public static class Event extends EventObject {
            private final String userId;
            private final String url;

            public Event(Seti source, String userId, String url) {
                super(source);
                this.userId = userId;
                this.url = url;
            }

            /**
             * @return the local Seti object
             */
            public Seti getSeti() {
                return (Seti)getSource();
            }

            /**
             * @return the userId associated to this presence event
             */
            public String getUserId() {
                return userId;
            }

            /**
             * @return the Oort URL where this presence event happened
             */
            public String getOortURL() {
                return url;
            }

            /**
             * @return whether this presence event happened on the local Seti or on a remote Seti
             */
            public boolean isLocal() {
                return getSeti().getOort().getURL().equals(getOortURL());
            }

            @Override
            public String toString() {
                return String.format("%s[%s %s on %s]",
                        getClass().getName(),
                        getUserId(),
                        isLocal() ? "local" : "remote",
                        getSeti());
            }
        }
    }

    private class CometListener implements Oort.CometListener {
        @Override
        public void cometJoined(Event event) {
            String oortURL = event.getCometURL();
            OortComet oortComet = _oort.findComet(oortURL);
            if (_logger.isDebugEnabled()) {
                _logger.debug("Comet joined: {} with {}", oortURL, oortComet);
            }
            if (oortComet != null) {
                ClientSessionChannel channel = oortComet.getChannel(generateSetiChannel(generateSetiId(oortURL)));
                Set<String> userIds = getAssociatedUserIds();
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Pushing associated users {} to comet {}", userIds, oortURL);
                }
                channel.publish(new SetiPresence(userIds));
            }
        }

        @Override
        public void cometLeft(Event event) {
            String oortURL = event.getCometURL();
            if (_logger.isDebugEnabled()) {
                _logger.debug("Comet left: {}", oortURL);
            }
            removePresences(oortURL);
        }
    }

    private class InitialStateListener implements ServerChannel.SubscriptionListener {
        @Override
        public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
            if (!session.isLocalSession()) {
                Set<String> associatedUserIds = getAssociatedUserIds();
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Broadcasting associated users {}", associatedUserIds);
                }
                SetiPresence presence = new SetiPresence(associatedUserIds);
                presence.put(SetiPresence.ALIVE_FIELD, true);
                session.deliver(_session, SETI_ALL_CHANNEL, presence, Promise.noop());
            }
        }
    }

    private class AllChannelsFilter implements BayeuxServer.SubscriptionListener, ServerSession.MessageListener {
        @Override
        public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
            if ("/**".equals(channel.getId()) && !session.isLocalSession() && !getOort().isOort(session)) {
                session.addListener(this);
            }
        }

        @Override
        public boolean onMessage(ServerSession session, ServerSession sender, ServerMessage message) {
            // Don't send Seti messages to the subscribers of the /** channel.
            if (message.getChannel().startsWith("/seti/")) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Dropping Seti message {} to channel '/**' subscriber {}", message, session);
                }
                return false;
            }
            return true;
        }
    }

    private class SetiAuthorizer implements Authorizer {
        @Override
        public Result authorize(Operation operation, ChannelId channel, ServerSession session, ServerMessage message) {
            if (session.isLocalSession() || getOort().isOort(session)) {
                return Result.grant();
            }
            return Result.ignore();
        }
    }
}
