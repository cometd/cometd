/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
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
 * TODO: review the paragraph below.
 * <p>A OortMembership holds the state for both the local and remote nodes.</p>
 * <p>A "comet joined" event is emitted on the local node for the remote node when
 * the local state is {@link LocalState#JOIN_SENT} and the remote state is
 * {@link RemoteState#JOIN_RECEIVED}.</p>
 */
// TODO: implement dump()
class OortMembership extends AbstractLifeCycle {
    // TODO: remove this constant.
    private static final String JOIN_MESSAGE_ATTRIBUTE = "oort.join";
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
                comets.add(cometInfo.getOortComet());
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
                    comet = cometInfo.getOortComet();
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
                result.add(cometInfo.getOortURL());
            }
        }
        return result;
    }

    OortComet getComet(String cometURL) {
        synchronized (lock) {
            for (ClientCometInfo cometInfo : clientComets.values()) {
                if (cometInfo.matchesURL(cometURL)) {
                    return cometInfo.getOortComet();
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
                oortComets.add(cometInfo.getOortComet());
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
                oortComets.add(cometInfo.getOortComet());
            }
        }
        for (OortComet oortComet : oortComets) {
            oortComet.unsubscribe(channelName);
        }
    }

    boolean containsServerSession(ServerSession session) {
        synchronized (lock) {
            for (ServerCometInfo cometInfo : serverComets.values()) {
                if (cometInfo.getServerSession().getId().equals(session.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean isCometConnected(String oortURL) {
        synchronized (lock) {
            for (ServerCometInfo serverCometInfo : serverComets.values()) {
                if (serverCometInfo.getOortURL().equals(oortURL)) {
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

    private enum LocalState {
        DISCONNECTED, HANDSHAKE_SENT, JOIN_SENT
    }

    private enum RemoteState {
        DISCONNECTED, HANDSHAKE_RECEIVED, JOIN_RECEIVED
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

            List<OortComet> staleOortComets = new ArrayList<>();
            ClientCometInfo clientCometInfo;
            ServerCometInfo serverCometInfo;
            boolean notify = false;
            synchronized (lock) {
                pendingComets.remove(cometURL);

                // Remove possibly stale information, e.g. when the other node restarted
                // we will have a stale oortId for the same oortURL we are processing now.
                Iterator<ClientCometInfo> iterator = clientComets.values().iterator();
                while (iterator.hasNext()) {
                    clientCometInfo = iterator.next();
                    if (clientCometInfo.matchesURL(cometURL) || clientCometInfo.matchesURL(oortURL)) {
                        iterator.remove();
                        staleOortComets.add(clientCometInfo.getOortComet());
                        if (logger.isDebugEnabled()) {
                            logger.debug("Unregistered client comet {}", clientCometInfo);
                        }
                    }
                }

                clientCometInfo = new ClientCometInfo(oortId, oortURL, oortComet);
                serverCometInfo = serverComets.get(oortId);

                if (message.isSuccessful()) {
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

                    // TODO: replace this with state on ServerInfo.
                    if (serverCometInfo != null) {
                        if (serverCometInfo.getServerSession().removeAttribute(JOIN_MESSAGE_ATTRIBUTE) != null) {
                            notify = true;
                        }
                    }
                }
            }

            for (OortComet comet : staleOortComets) {
                comet.disconnect();
            }

            if (message.isSuccessful()) {
                if (serverCometInfo != null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Server comet present {}", serverCometInfo);
                    }
                    // TODO: add listener to make sure the open succeeded.
                    oortComet.open();
                    if (notify) {
                        oort.notifyCometJoined(oortId, oortURL);
                    }
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Server comet not yet present");
                    }
                }
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("Handshake failed to comet {}, message {}", cometURL, message);
                }
                oortComet.disconnect();
            }
        }
    }

    private static abstract class CometInfo {
        private final String oortId;
        private final String oortURL;

        protected CometInfo(String oortId, String oortURL) {
            this.oortId = oortId;
            this.oortURL = oortURL;
        }

        protected String getOortId() {
            return oortId;
        }

        protected String getOortURL() {
            return oortURL;
        }

        @Override
        public String toString() {
            return String.format("%s@%x[%s|%s]", getClass().getSimpleName(), hashCode(), oortId, oortURL);
        }
    }

    private static class ServerCometInfo extends CometInfo {
        private final ServerSession session;

        private ServerCometInfo(String oortId, String oortURL, ServerSession session) {
            super(oortId, oortURL);
            this.session = session;
        }

        private ServerSession getServerSession() {
            return session;
        }

        @Override
        public String toString() {
            return String.format("%s[%s]", super.toString(), session);
        }
    }

    private static class ClientCometInfo extends CometInfo {
        private final OortComet oortComet;
        private Set<String> urls;

        private ClientCometInfo(String oortId, String oortURL, OortComet oortComet) {
            super(oortId, oortURL);
            this.oortComet = oortComet;
        }

        private OortComet getOortComet() {
            return oortComet;
        }

        private void addAliasURL(String url) {
            synchronized (this) {
                if (urls == null) {
                    urls = new HashSet<>();
                }
                urls.add(url);
            }
        }

        private boolean matchesURL(String url) {
            if (getOortURL().equals(url)) {
                return true;
            }

            synchronized (this) {
                return urls != null && urls.contains(url);
            }
        }

        @Override
        public String toString() {
            return String.format("%s[%s]%s", super.toString(), oortComet, Objects.toString(urls, ""));
        }
    }

    private class OortExtension extends BayeuxServer.Extension.Adapter {
        @Override
        public boolean sendMeta(ServerSession session, ServerMessage.Mutable reply) {
            if (!Channel.META_HANDSHAKE.equals(reply.getChannel())) {
                return true;
            }
            // Process only successful responses.
            if (!reply.isSuccessful()) {
                return true;
            }
            // Skip local sessions.
            if (session == null || session.isLocalSession()) {
                return true;
            }

            Map<String, Object> messageExt = reply.getAssociated().getExt();
            if (messageExt == null) {
                return true;
            }

            Object messageOortExtObject = messageExt.get(Oort.EXT_OORT_FIELD);
            if (messageOortExtObject instanceof Map) {
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
                    // Add the extension information even in case we're then disconnecting.
                    // The presence of the extension information will inform the client
                    // that the connection "succeeded" from the Oort point of view, but
                    // we add the extension information to drop it if it already exists.
                    Map<String, Object> replyExt = reply.getExt(true);
                    Map<String, Object> replyOortExt = new HashMap<>(2);
                    replyExt.put(Oort.EXT_OORT_FIELD, replyOortExt);
                    replyOortExt.put(Oort.EXT_OORT_URL_FIELD, oort.getURL());
                    replyOortExt.put(EXT_OORT_ID_FIELD, oort.getId());

                    ServerCometInfo serverCometInfo = new ServerCometInfo(remoteOortId, remoteOortURL, session);
                    ClientCometInfo clientCometInfo;
                    synchronized (lock) {
                        ServerCometInfo existing = serverComets.get(remoteOortId);
                        if (existing != null) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Comet already known {}", existing);
                            }
                            // TODO: we have an existing ServerSession from the same node.
                            //  Must disconnect the previous one and keep this one.
                        }
                        serverComets.put(remoteOortId, serverCometInfo);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Registered server comet {}", serverCometInfo);
                        }
                        clientCometInfo = clientComets.get(remoteOortId);
                    }

                    // Be notified when the remote comet stops.
                    session.addListener(new OortCometDisconnectListener());
                    // Prevent loops in sending/receiving messages.
                    session.addListener(new OortCometLoopListener());

                    if (clientCometInfo != null) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Client comet present {}", clientCometInfo);
                        }
                        // TODO: add message listener to handle failures.
                        //  How do we not call open twice, once here and once on HS reply?
                        clientCometInfo.getOortComet().open();
                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Client comet not yet present");
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
                Iterator<ServerCometInfo> cometInfos = serverComets.values().iterator();
                while (cometInfos.hasNext()) {
                    ServerCometInfo info = cometInfos.next();
                    if (info.getServerSession().getId().equals(session.getId())) {
                        cometInfos.remove();
                        serverCometInfo = info;
                        break;
                    }
                }
            }

            if (serverCometInfo != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Disconnected from {}", serverCometInfo);
                }
                String remoteOortId = serverCometInfo.getOortId();
                String remoteOortURL = serverCometInfo.getOortURL();

                if (!timeout) {
                    OortComet oortComet;
                    synchronized (lock) {
                        oortComet = pendingComets.remove(remoteOortURL);
                        if (oortComet == null) {
                            ClientCometInfo clientCometInfo = clientComets.remove(remoteOortId);
                            if (clientCometInfo != null) {
                                oortComet = clientCometInfo.getOortComet();
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
            // Prevent loops by not delivering a message from self or Oort session to remote Oort comets
            if (session.getId().equals(sender.getId()) || oort.isOort(sender)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} --| {} {}", sender, session, message);
                }
                return false;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("{} --> {} {}", sender, session, message);
            }
            return true;
        }
    }

    private class JoinListener implements ServerChannel.MessageListener {
        @Override
        public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
            Map<String, Object> data = message.getDataAsMap();
            String remoteOortId = (String)data.get(Oort.EXT_OORT_ID_FIELD);
            String remoteOortURL = (String)data.get(Oort.EXT_OORT_URL_FIELD);
            if (remoteOortURL != null && remoteOortId != null) {
                boolean ready = false;
                Set<String> staleComets = null;
                synchronized (lock) {
                    Iterator<ServerCometInfo> iterator = serverComets.values().iterator();
                    while (iterator.hasNext()) {
                        ServerCometInfo serverCometInfo = iterator.next();
                        if (remoteOortURL.equals(serverCometInfo.getOortURL())) {
                            String oortId = serverCometInfo.getOortId();
                            if (remoteOortId.equals(oortId)) {
                                if (clientComets.containsKey(remoteOortId)) {
                                    ready = true;
                                } else {
                                    serverCometInfo.getServerSession().setAttribute(JOIN_MESSAGE_ATTRIBUTE, message);
                                }
                            } else {
                                // We found a stale entry for a crashed node.
                                iterator.remove();
                                if (staleComets == null) {
                                    staleComets = new HashSet<>(1);
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
                if (ready) {
                    oort.notifyCometJoined(remoteOortId, remoteOortURL);
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Delaying comet joined: {}|{}", remoteOortId, remoteOortURL);
                    }
                }
            }
            return true;
        }
    }
}
