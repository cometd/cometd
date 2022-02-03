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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EventListener;
import java.util.EventObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.websocket.javax.WebSocketTransport;
import org.cometd.common.JSONContext;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.server.ext.BinaryExtension;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.DumpableCollection;
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
    static final String COMET_URL_ATTRIBUTE = EXT_OORT_FIELD + "." + EXT_COMET_URL_FIELD;

    private final ConcurrentMap<String, Boolean> _channels = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<CometListener> _cometListeners = new CopyOnWriteArrayList<>();
    private final ServerChannel.MessageListener _cloudListener = new CloudListener();
    private final List<ClientTransport.Factory> _transportFactories = new ArrayList<>();
    private final BayeuxServer _bayeux;
    private final String _url;
    private final String _id;
    private final Logger _logger;
    private final LocalSession _oortSession;
    private final OortMembership _membership;
    private ScheduledExecutorService _scheduler;
    private String _secret;
    private boolean _ackExtensionEnabled = true;
    private Extension _ackExtension;
    private boolean _binaryExtensionEnabled;
    private Extension _serverBinaryExtension;
    private ClientSession.Extension _binaryExtension;
    private JSONContext.Client _jsonContext;

    public Oort(BayeuxServer bayeux, String url) {
        _bayeux = bayeux;
        _url = url;
        _id = UUID.randomUUID().toString();
        _logger = LoggerFactory.getLogger(loggerName(getClass(), url, null));
        _oortSession = bayeux.newLocalSession("oort");
        _membership = new OortMembership(this);
        addBean(_membership);
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
            _transportFactories.add(new JettyHttpClientTransport.Factory(new HttpClient()));
        }
        for (ClientTransport.Factory factory : _transportFactories) {
            addBean(factory);
        }

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

        ServerChannel oortCloudChannel = _bayeux.createChannelIfAbsent(OORT_CLOUD_CHANNEL).getReference();
        oortCloudChannel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
        oortCloudChannel.addListener(_cloudListener);

        _oortSession.handshake();

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();

        _oortSession.disconnect();
        _oortSession.removeExtension(_binaryExtension);

        ServerChannel channel = _bayeux.getChannel(OORT_CLOUD_CHANNEL);
        if (channel != null) {
            channel.removeListener(_cloudListener);
            channel.removeAuthorizer(GrantAuthorizer.GRANT_ALL);
        }

        Extension binaryExtension = _serverBinaryExtension;
        _serverBinaryExtension = null;
        if (binaryExtension != null) {
            _bayeux.removeExtension(binaryExtension);
        }

        Extension ackExtension = _ackExtension;
        _ackExtension = null;
        if (ackExtension != null) {
            _bayeux.removeExtension(ackExtension);
        }

        _channels.clear();

        _scheduler.shutdown();

        for (ClientTransport.Factory factory : _transportFactories) {
            removeBean(factory);
        }
    }

    protected ScheduledExecutorService getScheduler() {
        return _scheduler;
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
        return _membership.observeComet(cometURL);
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

        maxMessageSizeOption = WebSocketTransport.PREFIX + "." + maxMessageSizeOption;
        option = _bayeux.getOption(maxMessageSizeOption);
        if (option != null) {
            options.put(maxMessageSizeOption, option);
        }

        String idleTimeoutOption = WebSocketTransport.PREFIX + "." + WebSocketTransport.IDLE_TIMEOUT_OPTION;
        option = _bayeux.getOption(idleTimeoutOption);
        if (option != null) {
            options.put(idleTimeoutOption, option);
        }

        String maxNetworkDelayOption = ClientTransport.MAX_NETWORK_DELAY_OPTION;
        option = _bayeux.getOption(maxNetworkDelayOption);
        if (option != null) {
            options.put(maxNetworkDelayOption, option);
        }

        List<ClientTransport> transports = new ArrayList<>();
        for (ClientTransport.Factory factory : getClientTransportFactories()) {
            transports.add(factory.newClientTransport(cometURL, options));
        }

        ClientTransport transport = transports.get(0);
        int size = transports.size();
        ClientTransport[] otherTransports = transports.subList(1, size).toArray(new ClientTransport[0]);

        return newOortComet(cometURL, transport, otherTransports);
    }

    protected OortComet newOortComet(String cometURL, ClientTransport transport, ClientTransport[] otherTransports) {
        return new OortComet(this, cometURL, getScheduler(), transport, otherTransports);
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
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] bytes = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new String(Base64.getEncoder().encode(bytes), StandardCharsets.UTF_8);
        } catch (Exception x) {
            throw new IllegalArgumentException(x);
        }
    }

    OortComet createOortComet(String cometURL) {
        return _membership.createOortComet(cometURL);
    }

    void connectComet(OortComet comet) {
        connectComet(comet, newOortHandshakeFields(comet.getURL(), null));
    }

    protected void connectComet(OortComet comet, Map<String, Object> fields) {
        comet.handshake(fields);
    }

    public OortComet deobserveComet(String cometURL) {
        return _membership.deobserveComet(cometURL);
    }

    /**
     * @return the set of known Oort comet servers URLs.
     */
    @ManagedAttribute(value = "URLs of known Oorts in the cluster", readonly = true)
    public Set<String> getKnownComets() {
        return _membership.getKnownComets();
    }

    /**
     * @param cometURL the URL of a Oort comet
     * @return the OortComet instance connected with the Oort comet with the given URL
     */
    public OortComet getComet(String cometURL) {
        return _membership.getComet(cometURL);
    }

    /**
     * @param cometURL the URL of a Oort comet
     * @return the OortComet instance connecting or connected with the Oort comet with the given URL
     */
    protected OortComet findComet(String cometURL) {
        return _membership.findComet(cometURL);
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
            _membership.observeChannels(observedChannels);
        }
    }

    @ManagedOperation(value = "Deobserves the given channel", impact = "ACTION")
    public void deobserveChannel(@Name(value = "channel", description = "The channel to deobserve") String channelId) {
        if (_channels.remove(channelId) != null) {
            _membership.deobserveChannel(channelId);
        }
    }

    /**
     * @param session the server session to test
     * @return whether the given server session is one of those created by the Oort internal working
     * @see #isOortHandshake(Message)
     */
    public boolean isOort(ServerSession session) {
        if (session == null) {
            return false;
        }

        String id = session.getId();

        if (id.equals(_oortSession.getId())) {
            return true;
        }

        if (_membership.containsServerSession(session)) {
            return true;
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
        return _membership.isCometConnected(oortURL);
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

    void notifyCometJoined(String remoteOortId, String remoteOortURL) {
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

    void notifyCometLeft(String remoteOortId, String remoteOortURL) {
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
        Object[] array = data instanceof List ? ((List<?>)data).toArray() : (Object[])data;
        for (Object element : array) {
            observeComet((String)element);
        }
    }

    public Set<String> getObservedChannels() {
        return new HashSet<>(_channels.keySet());
    }

    List<String> knownOortIds() {
        return _membership.knownOortIds();
    }

    /**
     * @return the oortSession
     */
    public LocalSession getOortSession() {
        return _oortSession;
    }

    static String loggerName(Class<?> klass, String oortURL, String name) {
        String result = klass.getName() + "." + replacePunctuation(oortURL, '_');
        if (name != null) {
            result += "." + name;
        }
        return result;
    }

    protected static String replacePunctuation(String source, char replacement) {
        String replaced = source.replaceAll("[^\\p{Alnum}]", String.valueOf(replacement));
        // Compact multiple consecutive replacement chars
        return replaced.replaceAll("(" + replacement + ")\\1+", "$1");
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException {
        dumpObjects(out, indent, new DumpableCollection("observed channels", _channels.keySet()));
    }

    @Override
    public String toString() {
        return String.format("%s[%s]", getClass().getSimpleName(), getURL());
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
        public default void cometJoined(Event event) {
        }

        /**
         * Callback method invoked when a comet leaves the cloud
         *
         * @param event the comet event
         */
        public default void cometLeft(Event event) {
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
}
