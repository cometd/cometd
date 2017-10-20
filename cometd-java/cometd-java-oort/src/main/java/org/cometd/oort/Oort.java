/*
 * Copyright (c) 2008-2017 the original author or authors.
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
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.JSONContext;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.server.ext.BinaryExtension;
import org.cometd.websocket.client.WebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Oort is the cluster manager that links one CometD server to a set of other CometD servers.</p>
 * <p>The Oort instance is created and configured by either {@link OortMulticastConfigServlet} or
 * {@link OortStaticConfigServlet}.</p>
 * <p>This class maintains a collection of {@link OortComet} instances to each
 * CometD server, created by calls to {@link #observeComet(String)}.</p>
 * <p>The key configuration parameter is the Oort URL, which is
 * full public URL of the CometD servlet to which the Oort instance is bound,
 * for example: {@code http://myserver:8080/context/cometd}.</p>
 * <p>Oort instances can be configured with a shared {@link #setSecret(String) secret}, which allows
 * the Oort instance to distinguish handshakes coming from remote clients from handshakes coming from
 * other Oort comets: the firsts may be subject to a stricter authentication policy than the seconds.</p>
 *
 * @see OortMulticastConfigServlet
 * @see OortStaticConfigServlet
 */
@ManagedObject("CometD cloud node")
public class Oort extends ContainerLifeCycle {
    public final static String OORT_ATTRIBUTE = Oort.class.getName();
    public static final String EXT_OORT_FIELD = "org.cometd.oort";
    public static final String EXT_OORT_URL_FIELD = "oortURL";
    public static final String EXT_OORT_ID_FIELD = "oortId";
    public static final String EXT_OORT_SECRET_FIELD = "oortSecret";
    public static final String EXT_COMET_URL_FIELD = "cometURL";
    public static final String EXT_OORT_ALIAS_URL_FIELD = "oortAliasURL";
    public static final String OORT_CLOUD_CHANNEL = "/oort/cloud";
    public static final String OORT_SERVICE_CHANNEL = "/service/oort";
    private static final String COMET_URL_ATTRIBUTE = EXT_OORT_FIELD + "." + EXT_COMET_URL_FIELD;
    private static final String JOIN_MESSAGE_ATTRIBUTE = Oort.class.getName() + ".joinMessage";

    private final Map<String, OortComet> _pendingComets = new HashMap<>();
    private final Map<String, ClientCometInfo> _clientComets = new HashMap<>();
    private final Map<String, ServerCometInfo> _serverComets = new HashMap<>();
    private final ConcurrentMap<String, Boolean> _channels = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<CometListener> _cometListeners = new CopyOnWriteArrayList<>();
    private final Extension _oortExtension = new OortExtension();
    private final ServerChannel.MessageListener _cloudListener = new CloudListener();
    private final ServerChannel.MessageListener _joinListener = new JoinListener();
    private final List<ClientTransport.Factory> _transportFactories = new ArrayList<>();
    private final BayeuxServer _bayeux;
    private final String _url;
    private final String _id;
    private final Logger _logger;
    private final LocalSession _oortSession;
    private final Object _lock = this;
    private ScheduledExecutorService _scheduler;
    private String _secret;
    private boolean _ackExtensionEnabled;
    private Extension _ackExtension;
    private boolean _binaryExtensionEnabled;
    private Extension _serverBinaryExtension;
    private ClientSession.Extension _binaryExtension;
    private JSONContext.Client _jsonContext;

    public Oort(BayeuxServer bayeux, String url) {
        _bayeux = bayeux;
        _url = url;
        _id = UUID.randomUUID().toString();

        _logger = LoggerFactory.getLogger(getClass().getName() + "." + replacePunctuation(_url, '_'));

        _oortSession = bayeux.newLocalSession("oort");
        _secret = Long.toHexString(new SecureRandom().nextLong());
    }

    @Override
    protected void doStart() throws Exception {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        _scheduler = scheduler;

        if (_transportFactories.isEmpty()) {
            _transportFactories.add(new WebSocketTransport.Factory());
            _transportFactories.add(new LongPollingTransport.Factory(new HttpClient()));
        }
        for (ClientTransport.Factory factory : _transportFactories) {
            addBean(factory);
        }

        super.doStart();

        if (isAckExtensionEnabled()) {
            boolean present = false;
            for (Extension extension : _bayeux.getExtensions()) {
                if (extension instanceof AcknowledgedMessagesExtension) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                _bayeux.addExtension(_ackExtension = new AcknowledgedMessagesExtension());
            }
        }

        if (isBinaryExtensionEnabled()) {
            _oortSession.addExtension(_binaryExtension = new org.cometd.client.ext.BinaryExtension());
            boolean present = false;
            for (Extension extension : _bayeux.getExtensions()) {
                if (extension instanceof BinaryExtension) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                _bayeux.addExtension(_serverBinaryExtension = new BinaryExtension());
            }
        }

        _bayeux.addExtension(_oortExtension);

        ServerChannel oortCloudChannel = _bayeux.createChannelIfAbsent(OORT_CLOUD_CHANNEL).getReference();
        oortCloudChannel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
        oortCloudChannel.addListener(_cloudListener);

        ServerChannel oortServiceChannel = _bayeux.createChannelIfAbsent(OORT_SERVICE_CHANNEL).getReference();
        oortServiceChannel.addListener(_joinListener);

        _oortSession.handshake();
    }

    @Override
    protected void doStop() throws Exception {
        _oortSession.disconnect();
        _oortSession.removeExtension(_binaryExtension);

        List<OortComet> comets = new ArrayList<>();
        synchronized (_lock) {
            comets.addAll(_pendingComets.values());
            _pendingComets.clear();
            for (ClientCometInfo cometInfo : _clientComets.values()) {
                comets.add(cometInfo.getOortComet());
            }
            _clientComets.clear();
            _serverComets.clear();
        }
        for (OortComet comet : comets) {
            comet.disconnect(1000);
        }

        _channels.clear();

        ServerChannel channel = _bayeux.getChannel(OORT_SERVICE_CHANNEL);
        if (channel != null) {
            channel.removeListener(_joinListener);
        }

        channel = _bayeux.getChannel(OORT_CLOUD_CHANNEL);
        if (channel != null) {
            channel.removeListener(_cloudListener);
            channel.removeAuthorizer(GrantAuthorizer.GRANT_ALL);
        }

        Extension ackExtension = _ackExtension;
        _ackExtension = null;
        if (ackExtension != null) {
            _bayeux.removeExtension(ackExtension);
        }
        Extension binaryExtension = _serverBinaryExtension;
        _serverBinaryExtension = null;
        if (binaryExtension != null) {
            _bayeux.removeExtension(binaryExtension);
        }

        _bayeux.removeExtension(_oortExtension);

        _scheduler.shutdown();

        super.doStop();

        for (ClientTransport.Factory factory : _transportFactories) {
            removeBean(factory);
        }
    }

    @ManagedAttribute(value = "The BayeuxServer of this Oort", readonly = true)
    public BayeuxServer getBayeuxServer() {
        return _bayeux;
    }

    /**
     * @return the public absolute URL of the Oort CometD server
     */
    @ManagedAttribute(value = "The URL of this Oort", readonly = true)
    public String getURL() {
        return _url;
    }

    @ManagedAttribute(value = "The unique ID of this Oort", readonly = true)
    public String getId() {
        return _id;
    }

    @ManagedAttribute("The secret of this Oort")
    public String getSecret() {
        return _secret;
    }

    public void setSecret(String secret) {
        this._secret = secret;
    }

    @ManagedAttribute("Whether the acknowledgement extension is enabled")
    public boolean isAckExtensionEnabled() {
        return _ackExtensionEnabled;
    }

    public void setAckExtensionEnabled(boolean value) {
        _ackExtensionEnabled = value;
    }

    @ManagedAttribute("Whether the binary extension is enabled")
    public boolean isBinaryExtensionEnabled() {
        return _binaryExtensionEnabled;
    }

    public void setBinaryExtensionEnabled(boolean value) {
        _binaryExtensionEnabled = value;
    }

    public JSONContext.Client getJSONContextClient() {
        return _jsonContext;
    }

    public void setJSONContextClient(JSONContext.Client jsonContext) {
        _jsonContext = jsonContext;
    }

    public List<ClientTransport.Factory> getClientTransportFactories() {
        return _transportFactories;
    }

    public void setClientTransportFactories(List<ClientTransport.Factory> factories) {
        _transportFactories.clear();
        _transportFactories.addAll(factories);
    }

    /**
     * <p>Connects (if not already connected) and observes another Oort instance
     * (identified by the given URL) via a {@link OortComet} instance.</p>
     *
     * @param cometURL the Oort URL to observe
     * @return The {@link OortComet} instance associated to the Oort instance identified by the URL
     * or null if the given Oort URL represent this Oort instance
     */
    public OortComet observeComet(String cometURL) {
        return observeComet(cometURL, null);
    }

    protected OortComet observeComet(String cometURL, String oortAliasURL) {
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

        if (_url.equals(cometURL)) {
            return null;
        }

        if (_logger.isDebugEnabled()) {
            _logger.debug("Observing comet {}", cometURL);
        }

        OortComet oortComet;
        synchronized (_lock) {
            oortComet = getComet(cometURL);
            if (oortComet != null) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Comet {} is already connected with {}", cometURL, oortComet);
                }
                return oortComet;
            }

            oortComet = _pendingComets.get(cometURL);
            if (oortComet != null) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Comet {} is already connecting with {}", cometURL, oortComet);
                }
                return oortComet;
            }

            oortComet = newOortComet(cometURL);
            configureOortComet(oortComet);
            _pendingComets.put(cometURL, oortComet);
        }

        oortComet.getChannel(Channel.META_HANDSHAKE).addListener(new HandshakeListener(cometURL, oortComet));

        if (_logger.isDebugEnabled()) {
            _logger.debug("Connecting to comet {} with {}", cometURL, oortComet);
        }

        Map<String, Object> fields = newOortHandshakeFields(cometURL, oortAliasURL);
        connectComet(oortComet, fields);
        return oortComet;
    }

    protected OortComet newOortComet(String cometURL) {
        Map<String, Object> options = new HashMap<>(2);
        options.put(ClientTransport.SCHEDULER_OPTION, _scheduler);

        JSONContext.Client jsonContext = getJSONContextClient();
        if (jsonContext != null) {
            options.put(ClientTransport.JSON_CONTEXT_OPTION, jsonContext);
        }

        String maxMessageSizeOption = ClientTransport.MAX_MESSAGE_SIZE_OPTION;
        Object option = _bayeux.getOption(maxMessageSizeOption);
        if (option != null) {
            options.put(maxMessageSizeOption, option);
        }

        maxMessageSizeOption = WebSocketTransport.PREFIX + "." + ClientTransport.MAX_MESSAGE_SIZE_OPTION;
        option = _bayeux.getOption(maxMessageSizeOption);
        if (option != null) {
            options.put(maxMessageSizeOption, option);
        }

        String idleTimeoutOption = WebSocketTransport.PREFIX + "." + WebSocketTransport.IDLE_TIMEOUT_OPTION;
        option = _bayeux.getOption(idleTimeoutOption);
        if (option != null) {
            options.put(idleTimeoutOption, option);
        }

        List<ClientTransport> transports = new ArrayList<>();
        for (ClientTransport.Factory factory : getClientTransportFactories()) {
            transports.add(factory.newClientTransport(cometURL, options));
        }

        ClientTransport transport = transports.get(0);
        int size = transports.size();
        ClientTransport[] otherTransports = transports.subList(1, size).toArray(new ClientTransport[size - 1]);

        return new OortComet(this, cometURL, _scheduler, transport, otherTransports);
    }

    protected void configureOortComet(OortComet oortComet) {
        if (isAckExtensionEnabled()) {
            boolean present = false;
            for (ClientSession.Extension extension : oortComet.getExtensions()) {
                if (extension instanceof AckExtension) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                oortComet.addExtension(new AckExtension());
            }
        }
        if (isBinaryExtensionEnabled()) {
            boolean present = false;
            for (ClientSession.Extension extension : oortComet.getExtensions()) {
                if (extension instanceof org.cometd.client.ext.BinaryExtension) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                oortComet.addExtension(new org.cometd.client.ext.BinaryExtension());
            }
        }
    }

    protected String encodeSecret(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return new String(B64Code.encode(digest.digest(secret.getBytes("UTF-8"))));
        } catch (Exception x) {
            throw new IllegalArgumentException(x);
        }
    }

    protected void connectComet(OortComet comet, Map<String, Object> fields) {
        comet.handshake(fields);
    }

    public OortComet deobserveComet(String cometURL) {
        if (_url.equals(cometURL)) {
            return null;
        }

        OortComet comet;
        synchronized (_lock) {
            comet = _pendingComets.remove(cometURL);
            if (comet != null) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Disconnecting pending comet {} with {}", cometURL, comet);
                }
            } else {
                Iterator<ClientCometInfo> cometInfos = _clientComets.values().iterator();
                while (cometInfos.hasNext()) {
                    ClientCometInfo cometInfo = cometInfos.next();
                    if (cometInfo.matchesURL(cometURL)) {
                        if (_logger.isDebugEnabled()) {
                            _logger.debug("Disconnecting comet {}", cometInfo);
                        }
                        comet = cometInfo.getOortComet();
                        cometInfos.remove();
                        break;
                    }
                }
            }
        }
        if (comet != null) {
            comet.disconnect();
        }

        return comet;
    }

    /**
     * @return the set of known Oort comet servers URLs.
     */
    @ManagedAttribute(value = "URLs of known Oorts in the cluster", readonly = true)
    public Set<String> getKnownComets() {
        Set<String> result = new HashSet<>();
        synchronized (_lock) {
            for (ClientCometInfo cometInfo : _clientComets.values()) {
                result.add(cometInfo.getOortURL());
            }
        }
        return result;
    }

    /**
     * @param cometURL the URL of a Oort comet
     * @return the OortComet instance connected with the Oort comet with the given URL
     */
    public OortComet getComet(String cometURL) {
        synchronized (_lock) {
            for (ClientCometInfo cometInfo : _clientComets.values()) {
                if (cometInfo.matchesURL(cometURL)) {
                    return cometInfo.getOortComet();
                }
            }
            return null;
        }
    }

    /**
     * @param cometURL the URL of a Oort comet
     * @return the OortComet instance connecting or connected with the Oort comet with the given URL
     */
    protected OortComet findComet(String cometURL) {
        synchronized (_lock) {
            OortComet result = _pendingComets.get(cometURL);
            if (result == null) {
                result = getComet(cometURL);
            }
            return result;
        }
    }

    /**
     * <p>Observes the given channel, registering to receive messages from
     * the Oort comets connected to this Oort instance.</p>
     * <p>Once observed, all {@link OortComet} instances subscribe
     * to the channel and will repeat any messages published to
     * the local channel (with loop prevention), so that the
     * messages are distributed to all Oort comet servers.</p>
     *
     * @param channelName the channel to observe
     */
    @ManagedOperation(value = "Observes the given channel", impact = "ACTION")
    public void observeChannel(@Name(value = "channel", description = "The channel to observe") String channelName) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Observing channel {}", channelName);
        }

        if (!ChannelId.isBroadcast(channelName)) {
            throw new IllegalArgumentException("Channel " + channelName + " cannot be observed because is not a broadcast channel");
        }

        if (_channels.putIfAbsent(channelName, Boolean.TRUE) == null) {
            Set<String> observedChannels = getObservedChannels();
            List<OortComet> oortComets = new ArrayList<>();
            synchronized (_lock) {
                for (ClientCometInfo cometInfo : _clientComets.values()) {
                    oortComets.add(cometInfo.getOortComet());
                }
            }
            for (OortComet oortComet : oortComets) {
                oortComet.subscribe(observedChannels);
            }
        }
    }

    @ManagedOperation(value = "Deobserves the given channel", impact = "ACTION")
    public void deobserveChannel(@Name(value = "channel", description = "The channel to deobserve") String channelId) {
        if (_channels.remove(channelId) != null) {
            List<OortComet> oortComets = new ArrayList<>();
            synchronized (_lock) {
                for (ClientCometInfo cometInfo : _clientComets.values()) {
                    oortComets.add(cometInfo.getOortComet());
                }
            }
            for (OortComet oortComet : oortComets) {
                oortComet.unsubscribe(channelId);
            }
        }
    }

    /**
     * @param session the server session to test
     * @return whether the given server session is one of those created by the Oort internal working
     * @see #isOortHandshake(Message)
     */
    public boolean isOort(ServerSession session) {
        String id = session.getId();

        if (id.equals(_oortSession.getId())) {
            return true;
        }

        synchronized (_lock) {
            for (ServerCometInfo cometInfo : _serverComets.values()) {
                if (cometInfo.getServerSession().getId().equals(session.getId())) {
                    return true;
                }
            }
        }

        return session.getAttribute(COMET_URL_ATTRIBUTE) != null;
    }

    /**
     * @param handshake the handshake message to test
     * @return whether the given handshake message is coming from another Oort comet
     * that has been configured with the same {@link #setSecret(String) secret}
     * @see #isOort(ServerSession)
     */
    public boolean isOortHandshake(Message handshake) {
        if (!Channel.META_HANDSHAKE.equals(handshake.getChannel())) {
            return false;
        }
        Map<String, Object> ext = handshake.getExt();
        if (ext == null) {
            return false;
        }
        Object oortExtObject = ext.get(EXT_OORT_FIELD);
        if (!(oortExtObject instanceof Map)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> oortExt = (Map<String, Object>)oortExtObject;
        String cometURL = (String)oortExt.get(EXT_COMET_URL_FIELD);
        if (!getURL().equals(cometURL)) {
            return false;
        }
        String b64RemoteSecret = (String)oortExt.get(EXT_OORT_SECRET_FIELD);
        String b64LocalSecret = encodeSecret(getSecret());
        return b64LocalSecret.equals(b64RemoteSecret);
    }

    protected Map<String, Object> newOortHandshakeFields(String cometURL, String oortAliasURL) {
        Map<String, Object> fields = new HashMap<>(1);
        Map<String, Object> ext = new HashMap<>(1);
        fields.put(Message.EXT_FIELD, ext);
        Map<String, Object> oortExt = new HashMap<>(4);
        ext.put(EXT_OORT_FIELD, oortExt);
        oortExt.put(EXT_OORT_URL_FIELD, getURL());
        oortExt.put(EXT_OORT_ID_FIELD, getId());
        String b64Secret = encodeSecret(getSecret());
        oortExt.put(EXT_OORT_SECRET_FIELD, b64Secret);
        oortExt.put(EXT_COMET_URL_FIELD, cometURL);
        if (oortAliasURL != null) {
            oortExt.put(EXT_OORT_ALIAS_URL_FIELD, oortAliasURL);
        }
        return fields;
    }

    /**
     * @param oortURL the comet URL to check for connection
     * @return whether the given comet is connected to this comet
     */
    protected boolean isCometConnected(String oortURL) {
        synchronized (_lock) {
            for (ServerCometInfo serverCometInfo : _serverComets.values()) {
                if (serverCometInfo.getOortURL().equals(oortURL)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * <p>Called to register the details of a successful handshake from another Oort comet.</p>
     *
     * @param oortExt the remote Oort information
     * @param session the server session that represent the connection with the remote Oort comet
     * @return false if a connection from a remote Oort has already been established, true otherwise
     */
    protected boolean incomingCometHandshake(Map<String, Object> oortExt, ServerSession session) {
        String remoteOortURL = (String)oortExt.get(EXT_OORT_URL_FIELD);
        String remoteOortId = (String)oortExt.get(EXT_OORT_ID_FIELD);
        ServerCometInfo serverCometInfo = new ServerCometInfo(remoteOortId, remoteOortURL, session);
        ClientCometInfo clientCometInfo;
        synchronized (_lock) {
            ServerCometInfo existing = _serverComets.get(remoteOortId);
            if (existing != null) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Comet already known {}", existing);
                }
                return false;
            }
            _serverComets.put(remoteOortId, serverCometInfo);
            if (_logger.isDebugEnabled()) {
                _logger.debug("Registered server comet {}", serverCometInfo);
            }
            clientCometInfo = _clientComets.get(remoteOortId);
        }

        // Be notified when the remote comet stops.
        session.addListener(new OortCometDisconnectListener());
        // Prevent loops in sending/receiving messages.
        session.addListener(new OortCometLoopListener());

        if (clientCometInfo != null) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Client comet present {}", clientCometInfo);
            }
            clientCometInfo.getOortComet().open();
        } else {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Client comet not yet present");
            }
        }

        return true;
    }

    /**
     * Registers the given listener to be notified of comet events.
     *
     * @param listener the listener to add
     * @see #removeCometListener(CometListener)
     */
    public void addCometListener(CometListener listener) {
        _cometListeners.add(listener);
    }

    /**
     * Deregisters the given listener from being notified of comet events.
     *
     * @param listener the listener to remove
     * @see #addCometListener(CometListener)
     */
    public void removeCometListener(CometListener listener) {
        _cometListeners.remove(listener);
    }

    /**
     * Deregisters all comet listeners.
     *
     * @see #addCometListener(CometListener)
     * @see #removeCometListener(CometListener)
     */
    public void removeCometListeners() {
        _cometListeners.clear();
    }

    private void notifyCometJoined(String remoteOortId, String remoteOortURL) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Comet joined: {}|{}", remoteOortId, remoteOortURL);
        }
        CometListener.Event event = new CometListener.Event(this, remoteOortId, remoteOortURL);
        for (CometListener cometListener : _cometListeners) {
            try {
                cometListener.cometJoined(event);
            } catch (Throwable x) {
                _logger.info("Exception while invoking listener " + cometListener, x);
            }
        }
    }

    private void notifyCometLeft(String remoteOortId, String remoteOortURL) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Comet left: {}|{}", remoteOortId, remoteOortURL);
        }
        CometListener.Event event = new CometListener.Event(this, remoteOortId, remoteOortURL);
        for (CometListener cometListener : _cometListeners) {
            try {
                cometListener.cometLeft(event);
            } catch (Throwable x) {
                _logger.info("Exception while invoking listener " + cometListener, x);
            }
        }
    }

    protected void joinComets(Message message) {
        Object data = message.getData();
        Object[] array = data instanceof List ? ((List)data).toArray() : (Object[])data;
        for (Object element : array) {
            observeComet((String)element);
        }
    }

    public Set<String> getObservedChannels() {
        return new HashSet<>(_channels.keySet());
    }

    List<String> knownOortIds() {
        List<String> result = new ArrayList<>();
        synchronized (_lock) {
            result.addAll(_clientComets.keySet());
        }
        return result;
    }

    /**
     * @return the oortSession
     */
    public LocalSession getOortSession() {
        return _oortSession;
    }

    protected static String replacePunctuation(String source, char replacement) {
        String replaced = source.replaceAll("[^\\p{Alnum}]", String.valueOf(replacement));
        // Compact multiple consecutive replacement chars
        return replaced.replaceAll("(" + replacement + ")\\1+", "$1");
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException {
        super.dump(out, indent);

        List<Dumpable> children = new ArrayList<>();
        Set<String> observedChannels = getObservedChannels();
        children.add(new DumpableCollection("observed channels: " + observedChannels.size(), observedChannels));
        List<ClientCometInfo> clientInfos;
        synchronized (_lock) {
            clientInfos = new ArrayList<>(_clientComets.values());
        }
        children.add(new DumpableCollection("connected comets: " + clientInfos.size(), clientInfos));

        ContainerLifeCycle.dump(out, indent, children);
    }

    @Override
    public String toString() {
        return String.format("%s[%s]", getClass().getSimpleName(), getURL());
    }

    /**
     * <p>Extension that detects incoming handshakes from other Oort servers.</p>
     *
     * @see Oort#incomingCometHandshake(Map, ServerSession)
     */
    protected class OortExtension implements Extension {
        @Override
        public boolean sendMeta(ServerSession session, Mutable reply) {
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

            Object messageOortExtObject = messageExt.get(EXT_OORT_FIELD);
            if (messageOortExtObject instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> messageOortExt = (Map<String, Object>)messageOortExtObject;
                String remoteOortURL = (String)messageOortExt.get(EXT_OORT_URL_FIELD);
                String cometURL = (String)messageOortExt.get(EXT_COMET_URL_FIELD);
                String remoteOortId = (String)messageOortExt.get(EXT_OORT_ID_FIELD);

                session.setAttribute(COMET_URL_ATTRIBUTE, remoteOortURL);

                if (_id.equals(remoteOortId)) {
                    // Connecting to myself: disconnect.
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("Detected self connect from {} to {}, disconnecting", remoteOortURL, cometURL);
                    }
                    disconnect(session, reply);
                } else {
                    // Add the extension information even in case we're then disconnecting.
                    // The presence of the extension information will inform the client
                    // that the connection "succeeded" from the Oort point of view, but
                    // we add the extension information to drop it if it already exists.
                    Map<String, Object> replyExt = reply.getExt(true);
                    Map<String, Object> replyOortExt = new HashMap<>(2);
                    replyExt.put(EXT_OORT_FIELD, replyOortExt);
                    replyOortExt.put(EXT_OORT_URL_FIELD, getURL());
                    replyOortExt.put(EXT_OORT_ID_FIELD, getId());

                    boolean connectBack = incomingCometHandshake(messageOortExt, session);
                    if (connectBack) {
                        String cometAliasURL = (String)messageOortExt.get(EXT_OORT_ALIAS_URL_FIELD);
                        if (cometAliasURL != null && findComet(cometAliasURL) != null) {
                            // We are connecting to a comet that it is connecting back to us
                            // so there is no need to connect back again (just to be disconnected)
                            if (_logger.isDebugEnabled()) {
                                _logger.debug("Comet {} exists with alias {}, avoiding to establish connection", remoteOortURL, cometAliasURL);
                            }
                        } else {
                            if (_logger.isDebugEnabled()) {
                                _logger.debug("Comet {} is unknown, establishing connection", remoteOortURL);
                            }
                            observeComet(remoteOortURL, cometURL);
                        }
                    } else {
                        disconnect(session, reply);
                    }
                }
            }

            return true;
        }

        private void disconnect(ServerSession session, Mutable message) {
            _bayeux.removeSession(session);
            message.setSuccessful(false);
            Map<String, Object> advice = message.getAdvice(true);
            advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
        }
    }

    /**
     * <p>This listener handles messages sent to {@code /oort/cloud} that contains the list of comets
     * connected to the Oort that just joined the cloud.</p>
     * <p>For example, if comets A and B are connected, and if comets C and D are connected, when connecting
     * A and C, a message is sent from A to C on {@code /oort/cloud} containing the comets connected
     * to A (in this case B). When C receives this message, it knows it has to connect to B also.</p>
     */
    protected class CloudListener implements ServerChannel.MessageListener {
        @Override
        public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
            if (!from.isLocalSession()) {
                joinComets(message);
            }
            return true;
        }
    }

    protected class JoinListener implements ServerChannel.MessageListener {
        @Override
        public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message) {
            Map<String, Object> data = message.getDataAsMap();
            String remoteOortId = (String)data.get(EXT_OORT_ID_FIELD);
            String remoteOortURL = (String)data.get(EXT_OORT_URL_FIELD);
            if (remoteOortURL != null && remoteOortId != null) {
                boolean ready = false;
                Set<String> staleComets = null;
                synchronized (_lock) {
                    Iterator<ServerCometInfo> iterator = _serverComets.values().iterator();
                    while (iterator.hasNext()) {
                        ServerCometInfo serverCometInfo = iterator.next();
                        if (remoteOortURL.equals(serverCometInfo.getOortURL())) {
                            String oortId = serverCometInfo.getOortId();
                            if (remoteOortId.equals(oortId)) {
                                if (_clientComets.containsKey(remoteOortId)) {
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
                        notifyCometLeft(oortId, remoteOortURL);
                    }
                }
                if (ready) {
                    notifyCometJoined(remoteOortId, remoteOortURL);
                } else {
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("Delaying comet joined: {}|{}", remoteOortId, remoteOortURL);
                    }
                }
            }
            return true;
        }
    }

    /**
     * <p>Listener interface that gets notified of comet events, that is when a new
     * comet joins the cloud or when a comet leaves the cloud.</p>
     * <p>If oortA and oortB form a cloud, when oortC joins to the cloud, both
     * oortA and oortB receive one event of type "comet joined", signaling that oortC
     * joined the cloud.</p>
     * <p>If, later, oortB leaves the cloud, then both oortA and oortC receive one
     * event of type "comet left" signaling that oortB left the cloud.</p>
     */
    public interface CometListener extends EventListener {
        /**
         * Callback method invoked when a new comet joins the cloud
         *
         * @param event the comet event
         */
        public void cometJoined(Event event);

        /**
         * Callback method invoked when a comet leaves the cloud
         *
         * @param event the comet event
         */
        public void cometLeft(Event event);

        /**
         * Empty implementation of {@link CometListener}
         */
        public static class Adapter implements CometListener {
            @Override
            public void cometJoined(Event event) {
            }

            @Override
            public void cometLeft(Event event) {
            }
        }

        /**
         * Comet event object delivered to {@link CometListener} methods.
         */
        public static class Event extends EventObject {
            private final String cometId;
            private final String cometURL;

            public Event(Oort source, String cometId, String cometURL) {
                super(source);
                this.cometId = cometId;
                this.cometURL = cometURL;
            }

            /**
             * @return the local Oort object
             */
            public Oort getOort() {
                return (Oort)getSource();
            }

            /**
             * @return the ID of the comet that generated the event
             */
            public String getCometId() {
                return cometId;
            }

            /**
             * @return the URL of the comet that generated the event
             */
            public String getCometURL() {
                return cometURL;
            }
        }
    }

    /**
     * <p>Listener that detect when a server session is removed (means that the remote
     * comet disconnected), and disconnects the OortComet associated.</p>
     */
    private class OortCometDisconnectListener implements ServerSession.RemoveListener {
        @Override
        public void removed(ServerSession session, boolean timeout) {
            ServerCometInfo serverCometInfo = null;
            synchronized (_lock) {
                Iterator<ServerCometInfo> cometInfos = _serverComets.values().iterator();
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
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Disconnected from {}", serverCometInfo);
                }
                String remoteOortId = serverCometInfo.getOortId();
                String remoteOortURL = serverCometInfo.getOortURL();

                if (!timeout) {
                    OortComet oortComet;
                    synchronized (_lock) {
                        oortComet = _pendingComets.remove(remoteOortURL);
                        if (oortComet == null) {
                            ClientCometInfo clientCometInfo = _clientComets.remove(remoteOortId);
                            if (clientCometInfo != null) {
                                oortComet = clientCometInfo.getOortComet();
                            }
                        }
                    }
                    if (oortComet != null) {
                        if (_logger.isDebugEnabled()) {
                            _logger.debug("Disconnecting from comet {} with {}", remoteOortURL, oortComet);
                        }
                        oortComet.disconnect();
                    }
                }

                // Do not notify if we are stopping.
                if (isRunning()) {
                    notifyCometLeft(remoteOortId, remoteOortURL);
                }
            }
        }
    }

    private class OortCometLoopListener implements ServerSession.MessageListener {
        @Override
        public boolean onMessage(ServerSession session, ServerSession sender, ServerMessage message) {
            // Prevent loops by not delivering a message from self or Oort session to remote Oort comets
            if (session.getId().equals(sender.getId()) || isOort(sender)) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("{} --| {} {}", sender, session, message);
                }
                return false;
            }
            if (_logger.isDebugEnabled()) {
                _logger.debug("{} --> {} {}", sender, session, message);
            }
            return true;
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
            String oortId = (String)oortExt.get(Oort.EXT_OORT_ID_FIELD);
            String oortURL = (String)oortExt.get(Oort.EXT_OORT_URL_FIELD);

            ClientCometInfo clientCometInfo;
            ServerCometInfo serverCometInfo;
            boolean ready = false;
            synchronized (_lock) {
                _pendingComets.remove(cometURL);

                Iterator<ClientCometInfo> iterator = _clientComets.values().iterator();
                while (iterator.hasNext()) {
                    clientCometInfo = iterator.next();
                    if (!clientCometInfo.getOortId().equals(oortId)) {
                        if (clientCometInfo.matchesURL(cometURL) || clientCometInfo.matchesURL(oortURL)) {
                            iterator.remove();
                            if (_logger.isDebugEnabled()) {
                                _logger.debug("Unregistered client comet {}", clientCometInfo);
                            }
                        }
                    }
                }

                clientCometInfo = _clientComets.get(oortId);
                if (clientCometInfo == null) {
                    clientCometInfo = new ClientCometInfo(oortId, oortURL, oortComet);
                    _clientComets.put(oortId, clientCometInfo);
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("Registered client comet {}", clientCometInfo);
                    }
                }

                serverCometInfo = _serverComets.get(oortId);
                if (serverCometInfo != null) {
                    if (serverCometInfo.getServerSession().removeAttribute(JOIN_MESSAGE_ATTRIBUTE) != null) {
                        ready = true;
                    }
                }
            }

            if (!cometURL.equals(oortURL)) {
                clientCometInfo.addAliasURL(cometURL);
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Added comet alias {}", clientCometInfo);
                }
            }

            if (message.isSuccessful()) {
                if (serverCometInfo != null) {
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("Server comet present {}", serverCometInfo);
                    }
                    oortComet.open();
                    if (ready) {
                        notifyCometJoined(oortId, oortURL);
                    }
                } else {
                    if (_logger.isDebugEnabled()) {
                        _logger.debug("Server comet not yet present");
                    }
                }
            } else {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Handshake failed to comet {}, message {}", cometURL, message);
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

    private static class DumpableCollection implements Dumpable {
        private final String name;
        private final Collection<?> collection;

        private DumpableCollection(String name, Collection<?> collection) {
            this.name = name;
            this.collection = collection;
        }

        public String dump() {
            return ContainerLifeCycle.dump(this);
        }

        public void dump(Appendable out, String indent) throws IOException {
            out.append(name).append(System.lineSeparator());
            if (collection != null) {
                ContainerLifeCycle.dump(out, indent, collection);
            }
        }
    }
}
