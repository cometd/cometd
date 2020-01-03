/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import static org.cometd.oort.Oort.EXT_OORT_ID_FIELD;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The Oort membership protocol is made of 3 steps:</p>
 * <ol>
 * <li>The local node sends the {@code /meta/handshake} request to connect to the remote node.</li>
 * <li>The remote node sends the {@code /meta/handshake} reply.</li>
 * <li>The local node sends a "join" message, composed of the channel subscriptions,
 * of the list of nodes connected to it, and a {@code /service/oort} message.</li>
 * </ol>
 * <p>Step 3. is necessary to make sure that the remote node has the
 * confirmation that the {@code /meta/handshake} reply was processed by the
 * local node.</p>
 */
class OortMembership extends AbstractLifeCycle implements Dumpable {
    private final Map<String, OortComet> pendingComets = new HashMap<>();
    private final Map<String, ClientCometInfo> clientComets = new HashMap<>();
    private final Map<String, ServerCometInfo> serverComets = new HashMap<>();
    private final BayeuxServer.Extension oortExtension = new OortExtension();
    private final ConfigurableServerChannel.ServerChannelListener joinListener = new JoinListener();
    private final Object lock = this;
    private final Oort oort;
    private final Logger logger;

    OortMembership(Oort oort) {
        this.oort = oort;
        this.logger = LoggerFactory.getLogger(getClass().getName() + "." + Oort.replacePunctuation(oort.getURL(), '_'));
    }

    @Override
    protected void doStart() throws Exception {
        BayeuxServer bayeuxServer = oort.getBayeuxServer();
        bayeuxServer.addExtension(oortExtension);
        ServerChannel oortServiceChannel = bayeuxServer.createChannelIfAbsent(Oort.OORT_SERVICE_CHANNEL).getReference();
        oortServiceChannel.addListener(joinListener);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception {
        disconnect();
        BayeuxServer bayeuxServer = oort.getBayeuxServer();
        ServerChannel channel = bayeuxServer.getChannel(Oort.OORT_SERVICE_CHANNEL);
        if (channel != null) {
            channel.removeListener(joinListener);
        }
        bayeuxServer.removeExtension(oortExtension);
        super.doStop();
    }

    private void disconnect() {
        List<OortComet> comets;
        synchronized (lock) {
            comets = new ArrayList<>(pendingComets.values());
            pendingComets.clear();
            for (ClientCometInfo cometInfo : clientComets.values()) {
                comets.add(cometInfo.oortComet);
            }
            clientComets.clear();
            serverComets.clear();
        }
        for (OortComet comet : comets) {
            comet.disconnect();
        }
    }

    OortComet observeComet(String cometURL) {
        return observeComet(cometURL, null);
    }

    OortComet deobserveComet(String cometURL) {
        if (oort.getURL().equals(cometURL)) {
            return null;
        }

        List<OortComet> comets = new ArrayList<>();
        synchronized (lock) {
            OortComet comet = pendingComets.remove(cometURL);
            if (comet != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Disconnecting pending comet {} with {}", cometURL, comet);
                }
                comets.add(comet);
            }
            Iterator<ClientCometInfo> cometInfos = clientComets.values().iterator();
            while (cometInfos.hasNext()) {
                ClientCometInfo cometInfo = cometInfos.next();
                if (cometInfo.matchesURL(cometURL)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Disconnecting comet {}", cometInfo);
                    }
                    comet = cometInfo.oortComet;
                    comets.add(comet);
                    cometInfos.remove();
                }
            }
        }
        for (OortComet comet : comets) {
            comet.disconnect();
        }

        return comets.isEmpty() ? null : comets.get(0);
    }

    Set<String> getKnownComets() {
        Set<String> result = new HashSet<>();
        synchronized (lock) {
            for (ClientCometInfo cometInfo : clientComets.values()) {
                result.add(cometInfo.oortURL);
            }
        }
        return result;
    }

    OortComet getComet(String cometURL) {
        synchronized (lock) {
            for (ClientCometInfo cometInfo : clientComets.values()) {
                if (cometInfo.matchesURL(cometURL)) {
                    return cometInfo.oortComet;
                }
            }
            return null;
        }
    }

    OortComet findComet(String cometURL) {
        synchronized (lock) {
            OortComet result = pendingComets.get(cometURL);
            if (result == null) {
                result = getComet(cometURL);
            }
            return result;
        }
    }

    private OortComet observeComet(String cometURL, String oortAliasURL) {
        try {
            URI uri = new URI(cometURL);
            if (uri.getScheme() == null) {
                throw new IllegalArgumentException("Missing protocol in comet URL " + cometURL);
            }
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("Missing host in comet URL " + cometURL);
            }
        } catch (URISyntaxException x) {
            throw new IllegalArgumentException(x);
        }

        if (oort.getURL().equals(cometURL)) {
            return null;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Observing comet {}", cometURL);
        }

        OortComet oortComet;
        synchronized (lock) {
            oortComet = oort.getComet(cometURL);
            if (oortComet != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Comet {} is already connected with {}", cometURL, oortComet);
                }
                return oortComet;
            }

            oortComet = pendingComets.get(cometURL);
            if (oortComet != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Comet {} is already connecting with {}", cometURL, oortComet);
                }
                return oortComet;
            }

            oortComet = createOortComet(cometURL);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Connecting to comet {} with {}", cometURL, oortComet);
        }

        Map<String, Object> fields = oort.newOortHandshakeFields(cometURL, oortAliasURL);
        oort.connectComet(oortComet, fields);
        return oortComet;
    }

    OortComet createOortComet(String cometURL) {
        synchronized (lock) {
            OortComet oortComet = oort.newOortComet(cometURL);
            oort.configureOortComet(oortComet);
            oortComet.getChannel(Channel.META_HANDSHAKE).addListener(new HandshakeListener(cometURL, oortComet));
            pendingComets.put(cometURL, oortComet);
            return oortComet;
        }
    }

    void observeChannels(Set<String> channels) {
        List<OortComet> oortComets = new ArrayList<>();
        synchronized (lock) {
            for (ClientCometInfo cometInfo : clientComets.values()) {
                oortComets.add(cometInfo.oortComet);
            }
        }
        for (OortComet oortComet : oortComets) {
            oortComet.subscribe(channels);
        }
    }

    void deobserveChannel(String channelName) {
        List<OortComet> oortComets = new ArrayList<>();
        synchronized (lock) {
            for (ClientCometInfo cometInfo : clientComets.values()) {
                oortComets.add(cometInfo.oortComet);
            }
        }
        for (OortComet oortComet : oortComets) {
            oortComet.unsubscribe(channelName);
        }
    }

    boolean containsServerSession(ServerSession session) {
        synchronized (lock) {
            for (ServerCometInfo cometInfo : serverComets.values()) {
                if (cometInfo.session.getId().equals(session.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean isCometConnected(String oortURL) {
        synchronized (lock) {
            for (ServerCometInfo serverCometInfo : serverComets.values()) {
                if (serverCometInfo.oortURL.equals(oortURL)) {
                    return true;
                }
            }
            return false;
        }
    }

    List<String> knownOortIds() {
        List<String> result;
        synchronized (lock) {
            result = new ArrayList<>(clientComets.keySet());
        }
        return result;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException {
        Collection<OortComet> pendingComets;
        Collection<ClientCometInfo> clientComets;
        Collection<ServerCometInfo> serverComets;
        synchronized (lock) {
            pendingComets = new ArrayList<>(this.pendingComets.values());
            clientComets = new ArrayList<>(this.clientComets.values());
            serverComets = new ArrayList<>(this.serverComets.values());
        }
        Dumpable.dumpObjects(out, indent, this,
                new DumpableCollection("pending", pendingComets),
                new DumpableCollection("clientComets", clientComets),
                new DumpableCollection("serverComets", serverComets));
    }

    @Override
    public String toString() {
        return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), oort.getURL());
    }

    private enum LocalState {
        HANDSHAKE_SENT, JOIN_SENT
    }

    private enum RemoteState {
        DISCONNECTED, HANDSHAKE_RECEIVED, JOIN_RECEIVED, JOINED
    }

    private static abstract class CometInfo {
        protected final String oortId;
        protected final String oortURL;

        protected CometInfo(String oortId, String oortURL) {
            this.oortId = oortId;
            this.oortURL = oortURL;
        }

        @Override
        public String toString() {
            return String.format("%s@%x[%s|%s]", getClass().getSimpleName(), hashCode(), oortId, oortURL);
        }
    }

    private static class ServerCometInfo extends CometInfo {
        private final ServerSession session;
        private RemoteState state = RemoteState.DISCONNECTED;

        private ServerCometInfo(String oortId, String oortURL, ServerSession session) {
            super(oortId, oortURL);
            this.session = session;
        }

        @Override
        public String toString() {
            return String.format("%s[%s,%s]", super.toString(), state, session);
        }
    }

    private static class ClientCometInfo extends CometInfo {
        private final OortComet oortComet;
        private Set<String> aliases;
        private LocalState state = LocalState.HANDSHAKE_SENT;

        private ClientCometInfo(String oortId, String oortURL, OortComet oortComet) {
            super(oortId, oortURL);
            this.oortComet = oortComet;
        }

        private void addAliasURL(String url) {
            synchronized (this) {
                if (aliases == null) {
                    aliases = new HashSet<>();
                }
                aliases.add(url);
            }
        }

        private boolean matchesURL(String url) {
            if (oortURL.equals(url)) {
                return true;
            }

            synchronized (this) {
                return aliases != null && aliases.contains(url);
            }
        }

        @Override
        public String toString() {
            return String.format("%s[%s,%s,aliases=%s]", super.toString(), state, oortComet, Objects.toString(aliases, "[]"));
        }
    }

    private class HandshakeListener implements ClientSessionChannel.MessageListener {
        private final String cometURL;
        private final OortComet oortComet;

        private HandshakeListener(String cometURL, OortComet oortComet) {
            this.cometURL = cometURL;
            this.oortComet = oortComet;
        }

        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received handshake reply message {}", message);
            }
            Map<String, Object> ext = message.getExt();
            if (ext == null) {
                return;
            }
            Object oortExtObject = ext.get(Oort.EXT_OORT_FIELD);
            if (!(oortExtObject instanceof Map)) {
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> oortExt = (Map<String, Object>)oortExtObject;
            String oortId = (String)oortExt.get(EXT_OORT_ID_FIELD);
            String oortURL = (String)oortExt.get(Oort.EXT_OORT_URL_FIELD);

            List<ClientCometInfo> staleClientCometInfos = new ArrayList<>();
            ClientCometInfo clientCometInfo;
            ServerCometInfo serverCometInfo;
            boolean notify = false;
            synchronized (lock) {
                // Remove possibly stale information, e.g. when the other node restarted
                // we will have a stale oortId for the same oortURL we are processing now.
                Iterator<ClientCometInfo> iterator = clientComets.values().iterator();
                while (iterator.hasNext()) {
                    clientCometInfo = iterator.next();
                    if (clientCometInfo.matchesURL(cometURL) || clientCometInfo.matchesURL(oortURL)) {
                        iterator.remove();
                        staleClientCometInfos.add(clientCometInfo);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Unregistered client comet {}", clientCometInfo);
                        }
                    }
                }

                clientCometInfo = new ClientCometInfo(oortId, oortURL, oortComet);
                serverCometInfo = serverComets.get(oortId);
                if (logger.isDebugEnabled()) {
                    logger.debug("Current server {}", serverCometInfo);
                }

                if (message.isSuccessful()) {
                    pendingComets.remove(cometURL);
                    clientComets.put(oortId, clientCometInfo);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Registered client comet {}", clientCometInfo);
                    }
                    if (!cometURL.equals(oortURL)) {
                        clientCometInfo.addAliasURL(cometURL);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Added comet alias {}", clientCometInfo);
                        }
                    }
                    if (serverCometInfo != null) {
                        clientCometInfo.state = LocalState.JOIN_SENT;
                        notify = serverCometInfo.state == RemoteState.JOIN_RECEIVED;
                        if (notify) {
                            serverCometInfo.state = RemoteState.JOINED;
                        }
                    }
                }
            }

            for (ClientCometInfo info : staleClientCometInfos) {
                OortComet comet = info.oortComet;
                if (comet != oortComet) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Disconnecting stale client comet {}", info);
                    }
                    comet.disconnect();
                }
            }

            if (message.isSuccessful()) {
                if (serverCometInfo != null) {
                    oortComet.open(new JoinCallback(oortComet));
                    if (notify) {
                        oort.notifyCometJoined(oortId, oortURL);
                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Skipping local join event: {}|{}", oortId, oortURL);
                        }
                    }
                }
            } else {
                // Handshake failed, we will retry it.
                if (logger.isDebugEnabled()) {
                    logger.debug("Handshake failed to comet {}, message {}", cometURL, message);
                }
            }
        }
    }

    private class OortExtension implements BayeuxServer.Extension {
        @Override
        public boolean sendMeta(ServerSession session, ServerMessage.Mutable reply) {
            if (!Channel.META_HANDSHAKE.equals(reply.getChannel())) {
                return true;
            }
            // Skip local sessions.
            if (session == null || session.isLocalSession()) {
                return true;
            }
            // Must be an Oort handshake.
            Map<String, Object> messageExt = reply.getAssociated().getExt();
            if (messageExt == null) {
                return true;
            }
            Object messageOortExtObject = messageExt.get(Oort.EXT_OORT_FIELD);
            if (!(messageOortExtObject instanceof Map)) {
                return true;
            }

            // Add the extension information even in case we will disconnect.
            // The presence of the extension information will inform the client
            // that the connection "succeeded" from the Oort point of view, but
            // we add the extension information to drop it if it already exists.
            Map<String, Object> replyExt = reply.getExt(true);
            Map<String, Object> replyOortExt = new HashMap<>(2);
            replyExt.put(Oort.EXT_OORT_FIELD, replyOortExt);
            replyOortExt.put(Oort.EXT_OORT_URL_FIELD, oort.getURL());
            replyOortExt.put(EXT_OORT_ID_FIELD, oort.getId());

            // Process only successful responses.
            if (!reply.isSuccessful()) {
                return true;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> messageOortExt = (Map<String, Object>)messageOortExtObject;
            String remoteOortURL = (String)messageOortExt.get(Oort.EXT_OORT_URL_FIELD);
            String cometURL = (String)messageOortExt.get(Oort.EXT_COMET_URL_FIELD);
            String remoteOortId = (String)messageOortExt.get(EXT_OORT_ID_FIELD);

            session.setAttribute(Oort.COMET_URL_ATTRIBUTE, remoteOortURL);

            if (oort.getId().equals(remoteOortId)) {
                // Connecting to myself: disconnect.
                if (logger.isDebugEnabled()) {
                    logger.debug("Detected self connect from {} to {}, disconnecting", remoteOortURL, cometURL);
                }
                disconnect(session, reply);
            } else {
                boolean sendJoin = false;
                ClientCometInfo clientCometInfo;
                ServerCometInfo existingServerCometInfo;
                ServerCometInfo serverCometInfo = new ServerCometInfo(remoteOortId, remoteOortURL, session);
                synchronized (lock) {
                    clientCometInfo = clientComets.get(remoteOortId);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Current client {}", clientCometInfo);
                    }
                    existingServerCometInfo = serverComets.put(remoteOortId, serverCometInfo);
                    if (existingServerCometInfo != null) {
                        serverCometInfo.state = existingServerCometInfo.state;
                    } else {
                        serverCometInfo.state = RemoteState.HANDSHAKE_RECEIVED;
                    }
                    if (logger.isDebugEnabled()) {
                        logger.debug("Registered server {}", serverCometInfo);
                    }
                    if (clientCometInfo != null) {
                        sendJoin = clientCometInfo.state == LocalState.HANDSHAKE_SENT;
                        if (sendJoin) {
                            clientCometInfo.state = LocalState.JOIN_SENT;
                        }
                    }
                }

                if (existingServerCometInfo != null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Server already known, disconnecting {}", existingServerCometInfo);
                    }
                    // Since it's only a session replace, there is no need to emit a "comet left"
                    // event, but we want to send a /meta/disconnect to the other node for the
                    // previous session. We want to do it from here because the RemoveListener
                    // won't find the previous session, as it has been replaced in the Map.
                    existingServerCometInfo.session.disconnect();
                }

                // Be notified when the remote comet stops.
                session.addListener(new OortCometDisconnectListener());
                // Prevent loops in sending/receiving messages.
                session.addListener(new OortCometLoopListener());

                if (clientCometInfo != null) {
                    if (sendJoin) {
                        OortComet oortComet = clientCometInfo.oortComet;
                        oortComet.open(new JoinCallback(oortComet));
                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Client already joined {}", clientCometInfo);
                        }
                    }
                }

                String cometAliasURL = (String)messageOortExt.get(Oort.EXT_OORT_ALIAS_URL_FIELD);
                if (cometAliasURL != null && oort.findComet(cometAliasURL) != null) {
                    // We are connecting to a comet that it is connecting back to us
                    // so there is no need to connect back again (just to be disconnected)
                    if (logger.isDebugEnabled()) {
                        logger.debug("Comet {} exists with alias {}, avoiding to establish connection", remoteOortURL, cometAliasURL);
                    }
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Comet {} is unknown, establishing connection", remoteOortURL);
                    }
                    observeComet(remoteOortURL, cometURL);
                }
            }

            return true;
        }

        private void disconnect(ServerSession session, ServerMessage.Mutable message) {
            oort.getBayeuxServer().removeSession(session);
            message.setSuccessful(false);
            Map<String, Object> advice = message.getAdvice(true);
            advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
        }
    }

    private class OortCometDisconnectListener implements ServerSession.RemoveListener {
        @Override
        public void removed(ServerSession session, boolean timeout) {
            ServerCometInfo serverCometInfo = null;
            synchronized (lock) {
                Iterator<ServerCometInfo> serverCometInfos = serverComets.values().iterator();
                while (serverCometInfos.hasNext()) {
                    ServerCometInfo info = serverCometInfos.next();
                    if (info.session.getId().equals(session.getId())) {
                        serverCometInfos.remove();
                        serverCometInfo = info;
                        break;
                    }
                }
            }

            if (serverCometInfo != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Disconnected from server {}", serverCometInfo);
                }
                String remoteOortId = serverCometInfo.oortId;
                String remoteOortURL = serverCometInfo.oortURL;

                if (!timeout) {
                    OortComet oortComet;
                    synchronized (lock) {
                        oortComet = pendingComets.remove(remoteOortURL);
                        if (oortComet == null) {
                            ClientCometInfo clientCometInfo = clientComets.remove(remoteOortId);
                            if (clientCometInfo != null) {
                                oortComet = clientCometInfo.oortComet;
                            }
                        }
                    }
                    if (oortComet != null) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Disconnecting from comet {} with {}", remoteOortURL, oortComet);
                        }
                        oortComet.disconnect();
                    }
                }

                // Do not notify if we are stopping.
                if (isRunning()) {
                    oort.notifyCometLeft(remoteOortId, remoteOortURL);
                }
            }
        }
    }

    private class OortCometLoopListener implements ServerSession.MessageListener {
        @Override
        public boolean onMessage(ServerSession session, ServerSession sender, ServerMessage message) {
            // Prevent loops by not delivering a message from self or Oort session to remote Oort comets.
            if (ChannelId.isBroadcast(message.getChannel()) && sender != null && (sender.getId().equals(session.getId()) || oort.isOort(sender))) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Blocked {} from {} to {}", message, sender, session);
                }
                return false;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Allowed {} from {} to {}", message, sender, session);
            }
            return true;
        }
    }

    private class JoinListener implements ServerChannel.MessageListener {
        @Override
        public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
            if (logger.isDebugEnabled()) {
                logger.debug("Received join message {}", message);
            }
            Map<String, Object> data = message.getDataAsMap();
            String remoteOortId = (String)data.get(Oort.EXT_OORT_ID_FIELD);
            String remoteOortURL = (String)data.get(Oort.EXT_OORT_URL_FIELD);
            if (remoteOortURL != null && remoteOortId != null) {
                boolean notify = false;
                Set<String> staleComets = null;
                synchronized (lock) {
                    ClientCometInfo clientCometInfo = clientComets.get(remoteOortId);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Current client {}", clientCometInfo);
                    }
                    Iterator<ServerCometInfo> iterator = serverComets.values().iterator();
                    while (iterator.hasNext()) {
                        ServerCometInfo serverCometInfo = iterator.next();
                        if (remoteOortURL.equals(serverCometInfo.oortURL)) {
                            String oortId = serverCometInfo.oortId;
                            if (remoteOortId.equals(oortId)) {
                                boolean handshakeReceived = serverCometInfo.state == RemoteState.HANDSHAKE_RECEIVED;
                                notify = clientCometInfo != null && handshakeReceived;
                                if (logger.isDebugEnabled()) {
                                    logger.debug("Current server {}", serverCometInfo);
                                }
                                if (handshakeReceived) {
                                    serverCometInfo.state = RemoteState.JOIN_RECEIVED;
                                }
                                if (notify) {
                                    serverCometInfo.state = RemoteState.JOINED;
                                }
                            } else {
                                // We found a stale entry for a crashed node.
                                if (logger.isDebugEnabled()) {
                                    logger.debug("Stale server {}", serverCometInfo);
                                }
                                iterator.remove();
                                if (staleComets == null) {
                                    staleComets = new HashSet<>(4);
                                }
                                staleComets.add(oortId);
                            }
                        }
                    }
                }
                if (staleComets != null) {
                    for (String oortId : staleComets) {
                        oort.notifyCometLeft(oortId, remoteOortURL);
                    }
                }
                if (notify) {
                    oort.notifyCometJoined(remoteOortId, remoteOortURL);
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Skipping remote join event: {}|{}", remoteOortId, remoteOortURL);
                    }
                }
            }
            return true;
        }
    }

    private class JoinCallback implements ClientSession.MessageListener, Runnable {
        private final OortComet oortComet;

        private JoinCallback(OortComet oortComet) {
            this.oortComet = oortComet;
        }

        @Override
        public void onMessage(Message message) {
            if (message.isSuccessful()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Join message successful {}", message);
                }
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("Join message failure, retrying {}", message);
                }
                // TODO: hardcoded delay.
                oort.getScheduler().schedule(this, 1, TimeUnit.SECONDS);
            }
        }

        @Override
        public void run() {
            oortComet.open(this);
        }
    }
}
