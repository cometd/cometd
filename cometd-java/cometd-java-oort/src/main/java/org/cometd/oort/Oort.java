/*
 * Copyright (c) 2008-2014 the original author or authors.
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
import java.util.Arrays;
import java.util.Collections;
import java.util.EventListener;
import java.util.EventObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
import org.cometd.common.HashMapMessage;
import org.cometd.common.JSONContext;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
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
 * for example: <code>http://myserver:8080/context/cometd</code>.</p>
 * <p>Oort instances can be configured with a shared {@link #setSecret(String) secret}, which allows
 * the Oort instance to distinguish handshakes coming from remote clients from handshakes coming from
 * other Oort comets: the firsts may be subject to a stricter authentication policy than the seconds.</p>
 *
 * @see OortMulticastConfigServlet
 * @see OortStaticConfigServlet
 */
@ManagedObject("CometD cloud node")
public class Oort extends ContainerLifeCycle
{
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

    private final ConcurrentMap<String, OortComet> _pendingComets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ClientCometInfo> _clientComets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ServerCometInfo> _serverComets = new ConcurrentHashMap<>();
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
    private ScheduledExecutorService _scheduler;
    private String _secret;
    private boolean _ackExtensionEnabled;
    private Extension _ackExtension;
    private JSONContext.Client _jsonContext;

    public Oort(BayeuxServer bayeux, String url)
    {
        _bayeux = bayeux;
        _url = url;
        _id = UUID.randomUUID().toString();

        _logger = LoggerFactory.getLogger(getClass().getName() + "." + replacePunctuation(_url, '_'));

        _oortSession = bayeux.newLocalSession("oort");
        _secret = Long.toHexString(new SecureRandom().nextLong());
    }

    @Override
    protected void doStart() throws Exception
    {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        _scheduler = scheduler;

        if (_transportFactories.isEmpty())
        {
            _transportFactories.add(new WebSocketTransport.Factory());
            _transportFactories.add(new LongPollingTransport.Factory(new HttpClient()));
        }
        for (ClientTransport.Factory factory : _transportFactories)
            addBean(factory);

        super.doStart();

        if (isAckExtensionEnabled())
        {
            boolean present = false;
            for (Extension extension : _bayeux.getExtensions())
            {
                if (extension instanceof AcknowledgedMessagesExtension)
                {
                    present = true;
                    break;
                }
            }
            if (!present)
                _bayeux.addExtension(_ackExtension = new AcknowledgedMessagesExtension());
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
    protected void doStop() throws Exception
    {
        _oortSession.disconnect();

        for (OortComet comet : _pendingComets.values())
            comet.disconnect(1000);
        _pendingComets.clear();

        for (ClientCometInfo cometInfo : _clientComets.values())
            cometInfo.comet.disconnect(1000);
        _clientComets.clear();

        _serverComets.clear();
        _channels.clear();

        ServerChannel channel = _bayeux.getChannel(OORT_SERVICE_CHANNEL);
        if (channel != null)
            channel.removeListener(_joinListener);

        channel = _bayeux.getChannel(OORT_CLOUD_CHANNEL);
        if (channel != null)
        {
            channel.removeListener(_cloudListener);
            channel.removeAuthorizer(GrantAuthorizer.GRANT_ALL);
        }

        Extension ackExtension = _ackExtension;
        _ackExtension = null;
        if (ackExtension != null)
            _bayeux.removeExtension(ackExtension);

        _bayeux.removeExtension(_oortExtension);

        _scheduler.shutdownNow();

        super.doStop();
    }

    @ManagedAttribute(value = "The BayeuxServer of this Oort", readonly = true)
    public BayeuxServer getBayeuxServer()
    {
        return _bayeux;
    }

    /**
     * @return the public absolute URL of the Oort CometD server
     */
    @ManagedAttribute(value = "The URL of this Oort", readonly = true)
    public String getURL()
    {
        return _url;
    }

    @ManagedAttribute(value = "The unique ID of this Oort", readonly = true)
    public String getId()
    {
        return _id;
    }

    @ManagedAttribute("The secret of this Oort")
    public String getSecret()
    {
        return _secret;
    }

    public void setSecret(String secret)
    {
        this._secret = secret;
    }

    @ManagedAttribute("Whether the acknowledgement extension is enabled")
    public boolean isAckExtensionEnabled()
    {
        return _ackExtensionEnabled;
    }

    public void setAckExtensionEnabled(boolean value)
    {
        _ackExtensionEnabled = value;
    }

    public JSONContext.Client getJSONContextClient()
    {
        return _jsonContext;
    }

    public void setJSONContextClient(JSONContext.Client jsonContext)
    {
        _jsonContext = jsonContext;
    }

    public List<ClientTransport.Factory> getClientTransportFactories()
    {
        return _transportFactories;
    }

    /**
     * <p>Connects (if not already connected) and observes another Oort instance
     * (identified by the given URL) via a {@link OortComet} instance.</p>
     *
     * @param cometURL the Oort URL to observe
     * @return The {@link OortComet} instance associated to the Oort instance identified by the URL
     *         or null if the given Oort URL represent this Oort instance
     */
    public OortComet observeComet(String cometURL)
    {
        return observeComet(cometURL, null);
    }

    protected OortComet observeComet(String cometURL, String cometAliasURL)
    {
        _logger.debug("Observing comet {}", cometURL);
        try
        {
            URI uri = new URI(cometURL);
            if (uri.getScheme() == null)
                throw new IllegalArgumentException("Missing protocol in comet URL " + cometURL);
            if (uri.getHost() == null)
                throw new IllegalArgumentException("Missing host in comet URL " + cometURL);
        }
        catch (URISyntaxException x)
        {
            throw new IllegalArgumentException(x);
        }

        if (_url.equals(cometURL))
            return null;

        OortComet comet = getComet(cometURL);
        if (comet != null)
        {
            _logger.debug("Comet {} is already connected", cometURL);
            return comet;
        }

        comet = newOortComet(cometURL);
        configureOortComet(comet);
        OortComet existing = _pendingComets.putIfAbsent(cometURL, comet);
        if (existing != null)
        {
            _logger.debug("Comet {} is already connecting", cometURL);
            return existing;
        }

        comet.getChannel(Channel.META_HANDSHAKE).addListener(new HandshakeListener(cometURL, comet));

        _logger.debug("Connecting to comet {}", cometURL);
        String b64Secret = encodeSecret(getSecret());
        Message.Mutable fields = new HashMapMessage();
        Map<String, Object> ext = fields.getExt(true);
        Map<String, Object> oortExt = new HashMap<>(4);
        ext.put(EXT_OORT_FIELD, oortExt);
        oortExt.put(EXT_OORT_URL_FIELD, getURL());
        oortExt.put(EXT_OORT_ID_FIELD, getId());
        oortExt.put(EXT_OORT_SECRET_FIELD, b64Secret);
        oortExt.put(EXT_COMET_URL_FIELD, cometURL);
        if (cometAliasURL != null)
            oortExt.put(EXT_OORT_ALIAS_URL_FIELD, cometAliasURL);
        connectComet(comet, fields);
        return comet;
    }

    protected OortComet newOortComet(String cometURL)
    {
        Map<String, Object> options = new HashMap<>(2);
        JSONContext.Client jsonContext = getJSONContextClient();
        if (jsonContext != null)
            options.put(ClientTransport.JSON_CONTEXT_OPTION, jsonContext);
        String maxMessageSizeOption = WebSocketTransport.PREFIX + "." + WebSocketTransport.MAX_MESSAGE_SIZE_OPTION;
        Object option = _bayeux.getOption(maxMessageSizeOption);
        if (option != null)
        {
            long value = option instanceof Number ? ((Number)option).longValue() : Long.parseLong(option.toString());
            options.put(maxMessageSizeOption, value);
        }
        options.put(ClientTransport.SCHEDULER_OPTION, _scheduler);

        List<ClientTransport> transports = new ArrayList<>();
        for (ClientTransport.Factory factory : getClientTransportFactories())
            transports.add(factory.newClientTransport(cometURL, options));

        ClientTransport transport = transports.get(0);
        int size = transports.size();
        ClientTransport[] otherTransports = transports.subList(1, size).toArray(new ClientTransport[size - 1]);

        return new OortComet(this, cometURL, _scheduler, transport, otherTransports);
    }

    protected void configureOortComet(OortComet oortComet)
    {
        if (isAckExtensionEnabled())
        {
            boolean present = false;
            for (ClientSession.Extension extension : oortComet.getExtensions())
            {
                if (extension instanceof AckExtension)
                {
                    present = true;
                    break;
                }
            }
            if (!present)
                oortComet.addExtension(new AckExtension());
        }
    }

    protected String encodeSecret(String secret)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return new String(B64Code.encode(digest.digest(secret.getBytes("UTF-8"))));
        }
        catch (Exception x)
        {
            throw new IllegalArgumentException(x);
        }
    }

    protected void connectComet(OortComet comet, Message.Mutable fields)
    {
        comet.handshake(fields);
    }

    public OortComet deobserveComet(String cometURL)
    {
        if (_url.equals(cometURL))
            return null;

        OortComet comet = _pendingComets.remove(cometURL);
        if (comet != null)
        {
            _logger.debug("Disconnecting pending comet {}", cometURL);
            comet.disconnect();
        }

        Iterator<ClientCometInfo> cometInfos = _clientComets.values().iterator();
        while (cometInfos.hasNext())
        {
            ClientCometInfo cometInfo = cometInfos.next();
            if (cometInfo.matchesURL(cometURL))
            {
                _logger.debug("Disconnecting comet {}", cometURL);
                comet = cometInfo.getOortComet();
                comet.disconnect();
                cometInfos.remove();
                break;
            }
        }

        return comet;
    }

    /**
     * @return the set of known Oort comet servers URLs.
     */
    @ManagedAttribute(value = "URLs of known Oorts in the cluster", readonly = true)
    public Set<String> getKnownComets()
    {
        Set<String> result = new HashSet<>();
        for (ClientCometInfo cometInfo : _clientComets.values())
            result.add(cometInfo.getOortURL());
        return result;
    }

    /**
     * @param cometURL the URL of a Oort comet
     * @return the OortComet instance connected with the Oort comet with the given URL
     */
    public OortComet getComet(String cometURL)
    {
        for (ClientCometInfo cometInfo : _clientComets.values())
        {
            if (cometInfo.matchesURL(cometURL))
                return cometInfo.getOortComet();
        }
        return null;
    }

    /**
     *
     * @param cometURL the URL of a Oort comet
     * @return the OortComet instance connecting or connected with the Oort comet with the given URL
     */
    protected OortComet findComet(String cometURL)
    {
        OortComet result = getComet(cometURL);
        if (result == null)
            result = _pendingComets.get(cometURL);
        return result;
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
    public void observeChannel(@Name(value = "channel", description = "The channel to observe") String channelName)
    {
        _logger.debug("Observing channel {}", channelName);

        if (!ChannelId.isBroadcast(channelName))
            throw new IllegalArgumentException("Channel " + channelName + " cannot be observed because is not a broadcast channel");

        if (_channels.putIfAbsent(channelName, Boolean.TRUE) == null)
        {
            Set<String> observedChannels = getObservedChannels();
            for (ClientCometInfo cometInfo : _clientComets.values())
                cometInfo.getOortComet().subscribe(observedChannels);
        }
    }

    @ManagedOperation(value = "Deobserves the given channel", impact = "ACTION")
    public void deobserveChannel(@Name(value = "channel", description = "The channel to deobserve") String channelId)
    {
        if (_channels.remove(channelId) != null)
        {
            for (ClientCometInfo cometInfo : _clientComets.values())
                cometInfo.getOortComet().unsubscribe(channelId);
        }
    }

    /**
     * @param session the server session to test
     * @return whether the given server session is one of those created by the Oort internal working
     * @see #isOortHandshake(Message)
     */
    public boolean isOort(ServerSession session)
    {
        String id = session.getId();

        if (id.equals(_oortSession.getId()))
            return true;

        for (ServerCometInfo cometInfo : _serverComets.values())
        {
            if (cometInfo.getServerSession().getId().equals(session.getId()))
                return true;
        }

        return false;
    }

    /**
     * @param handshake the handshake message to test
     * @return whether the given handshake message is coming from another Oort comet
     * that has been configured with the same {@link #setSecret(String) secret}
     * @see #isOort(ServerSession)
     */
    public boolean isOortHandshake(Message handshake)
    {
        if (!Channel.META_HANDSHAKE.equals(handshake.getChannel()))
            return false;
        Map<String, Object> ext = handshake.getExt();
        if (ext == null)
            return false;
        Object oortExtObject = ext.get(EXT_OORT_FIELD);
        if (!(oortExtObject instanceof Map))
            return false;
        @SuppressWarnings("unchecked")
        Map<String, Object> oortExt = (Map<String, Object>)oortExtObject;
        String cometURL = (String)oortExt.get(EXT_COMET_URL_FIELD);
        if (!getURL().equals(cometURL))
            return false;
        String b64RemoteSecret = (String)oortExt.get(EXT_OORT_SECRET_FIELD);
        String b64LocalSecret = encodeSecret(getSecret());
        return b64LocalSecret.equals(b64RemoteSecret);
    }

    /**
     * @param oortURL the comet URL to check for connection
     * @return whether the given comet is connected to this comet
     */
    protected boolean isCometConnected(String oortURL)
    {
        for (ServerCometInfo serverCometInfo : _serverComets.values())
        {
            if (serverCometInfo.getOortURL().equals(oortURL))
                return true;
        }
        return false;
    }

    /**
     * <p>Called to register the details of a successful handshake from another Oort comet.</p>
     *
     * @param oortExt the remote Oort information
     * @param session the server session that represent the connection with the remote Oort comet
     * @return false if a connection from a remote Oort has already been established, true otherwise
     */
    protected boolean incomingCometHandshake(Map<String, Object> oortExt, ServerSession session)
    {
        String remoteOortURL = (String)oortExt.get(EXT_OORT_URL_FIELD);
        _logger.debug("Incoming comet handshake from comet {} with {}", remoteOortURL, session);

        String remoteOortId = (String)oortExt.get(EXT_OORT_ID_FIELD);
        ServerCometInfo serverCometInfo = new ServerCometInfo(remoteOortId, remoteOortURL, session);
        ServerCometInfo existing = _serverComets.putIfAbsent(remoteOortId, serverCometInfo);
        if (existing != null)
        {
            _logger.debug("Comet {} is already known with {}", remoteOortURL, existing.getServerSession());
            return false;
        }

        session.setAttribute(COMET_URL_ATTRIBUTE, remoteOortURL);

        // Be notified when the remote comet stops
        session.addListener(new OortCometDisconnectListener(remoteOortURL, remoteOortId));
        // Prevent loops in sending/receiving messages
        session.addListener(new OortCometLoopListener());

        return true;
    }

    /**
     * Registers the given listener to be notified of comet events.
     * @param listener the listener to add
     */
    public void addCometListener(CometListener listener)
    {
        _cometListeners.add(listener);
    }

    /**
     * Deregisters the given listener from being notified of comet events.
     * @param listener the listener to remove
     */
    public void removeCometListener(CometListener listener)
    {
        _cometListeners.remove(listener);
    }

    private void notifyCometJoined(String remoteURL)
    {
        CometListener.Event event = new CometListener.Event(this, remoteURL);
        for (CometListener cometListener : _cometListeners)
        {
            try
            {
                cometListener.cometJoined(event);
            }
            catch (Throwable x)
            {
                _logger.info("Exception while invoking listener " + cometListener, x);
            }
        }
    }

    private void notifyCometLeft(String remoteURL)
    {
        CometListener.Event event = new CometListener.Event(this, remoteURL);
        for (CometListener cometListener : _cometListeners)
        {
            try
            {
                cometListener.cometLeft(event);
            }
            catch (Throwable x)
            {
                _logger.info("Exception while invoking listener " + cometListener, x);
            }
        }
    }

    protected void joinComets(Message message)
    {
        Object data = message.getData();
        Object[] array = data instanceof List ? ((List)data).toArray() : (Object[])data;
        for (Object element : array)
            observeComet((String)element);
    }

    public Set<String> getObservedChannels()
    {
        return new HashSet<>(_channels.keySet());
    }

    /**
     * @return the oortSession
     */
    public LocalSession getOortSession()
    {
        return _oortSession;
    }

    protected static String replacePunctuation(String source, char replacement)
    {
        String replaced = source.replaceAll("[^\\p{Alnum}]", String.valueOf(replacement));
        // Compact multiple consecutive replacement chars
        return replaced.replaceAll("(" + replacement + ")\\1+", "$1");
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        super.dump(out, indent);
        final String eol = System.getProperty("line.separator");
        Dumpable comets = new Dumpable()
        {
            public String dump()
            {
                return null;
            }

            public void dump(Appendable out, String indent) throws IOException
            {
                Set<String> knownComets = getKnownComets();
                out.append("Connected comets: ").append(String.valueOf(knownComets.size())).append(eol);
                ContainerLifeCycle.dump(out, indent, knownComets);
            }
        };
        Dumpable channels = new Dumpable()
        {
            public String dump()
            {
                return null;
            }

            public void dump(Appendable out, String indent) throws IOException
            {
                Set<String> observedChannels = getObservedChannels();
                out.append("Observed channels: ").append(String.valueOf(observedChannels.size())).append(eol);
                ContainerLifeCycle.dump(out, indent, observedChannels);
            }
        };
        ContainerLifeCycle.dump(out, indent, Arrays.asList(comets, channels));
    }

    public String toString()
    {
        return String.format("%s[%s]", getClass().getSimpleName(), getURL());
    }

    /**
     * <p>Extension that detects incoming handshakes from other Oort servers.</p>
     *
     * @see Oort#incomingCometHandshake(Map, ServerSession)
     */
    protected class OortExtension extends Extension.Adapter
    {
        @Override
        public boolean sendMeta(ServerSession to, Mutable message)
        {
            // Skip local sessions
            if (to == null)
                return true;

            if (!Channel.META_HANDSHAKE.equals(message.getChannel()))
                return true;

            // Process only successful responses
            if (!message.isSuccessful())
                return true;

            Map<String, Object> associatedExt = message.getAssociated().getExt();
            if (associatedExt == null)
                return true;

            Object associatedOortExtObject = associatedExt.get(EXT_OORT_FIELD);
            if (associatedOortExtObject instanceof Map)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> associatedOortExt = (Map<String, Object>)associatedOortExtObject;
                String remoteOortURL = (String)associatedOortExt.get(EXT_OORT_URL_FIELD);
                String cometURL = (String)associatedOortExt.get(EXT_COMET_URL_FIELD);
                String remoteOortId = (String)associatedOortExt.get(EXT_OORT_ID_FIELD);
                if (_id.equals(remoteOortId))
                {
                    // Connecting to myself: disconnect
                    _logger.warn("Detected self connect from {} to {}, disconnecting", remoteOortURL, cometURL);
                    disconnect(message);
                }
                else
                {
                    // Add the extension information even in case we're then disconnecting.
                    // The presence of the extension information will inform the client
                    // that the connection "succeeded" from the Oort point of view, but
                    // we add the advice information to drop it because if it already exists.
                    Map<String, Object> ext = message.getExt(true);
                    Map<String, Object> oortExt = new HashMap<>(2);
                    ext.put(EXT_OORT_FIELD, oortExt);
                    oortExt.put(EXT_OORT_URL_FIELD, getURL());
                    oortExt.put(EXT_OORT_ID_FIELD, getId());

                    boolean connectBack = incomingCometHandshake(Collections.unmodifiableMap(associatedOortExt), to);
                    if (connectBack)
                    {
                        String cometAliasURL = (String)associatedOortExt.get(EXT_OORT_ALIAS_URL_FIELD);
                        if (cometAliasURL != null && _pendingComets.containsKey(cometAliasURL))
                        {
                            // We are connecting to a comet that it is connecting back to us
                            // so there is not need to connect back again (just to be disconnected)
                            _logger.debug("Comet {} is pending with alias {}, avoiding to establish connection", remoteOortURL, cometAliasURL);
                        }
                        else
                        {
                            _logger.debug("Comet {} is unknown, establishing connection", remoteOortURL);
                            observeComet(remoteOortURL, cometURL);
                        }
                    }
                    else
                    {
                        disconnect(message);
                    }
                }
            }

            return true;
        }

        private void disconnect(Mutable message)
        {
            message.setSuccessful(false);
            Map<String, Object> advice = message.getAdvice(true);
            advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
        }
    }

    /**
     * <p>This listener handles messages sent to <code>/oort/cloud</code> that contains the list of comets
     * connected to the Oort that just joined the cloud.</p>
     * <p>For example, if comets A and B are connected, and if comets C and D are connected, when connecting
     * A and C, a message is sent from A to C on <code>/oort/cloud</code> containing the comets connected
     * to A (in this case B). When C receives this message, it knows it has to connect to B also.</p>
     */
    protected class CloudListener implements ServerChannel.MessageListener
    {
        public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
        {
            if (!from.isLocalSession())
                joinComets(message);
            return true;
        }
    }

    protected class JoinListener implements ServerChannel.MessageListener
    {
        public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
        {
            Map<String, Object> data = message.getDataAsMap();
            String remoteOortURL = (String)data.get(EXT_OORT_URL_FIELD);
            if (remoteOortURL != null)
            {
                _logger.debug("Comet {} joined", remoteOortURL);
                notifyCometJoined(remoteOortURL);
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
    public interface CometListener extends EventListener
    {
        /**
         * Callback method invoked when a new comet joins the cloud
         * @param event the comet event
         */
        public void cometJoined(Event event);

        /**
         * Callback method invoked when a comet leaves the cloud
         * @param event the comet event
         */
        public void cometLeft(Event event);

        /**
         * Empty implementation of {@link CometListener}
         */
        public static class Adapter implements CometListener
        {
            public void cometJoined(Event event)
            {
            }

            public void cometLeft(Event event)
            {
            }
        }

        /**
         * Comet event object delivered to {@link CometListener} methods.
         */
        public static class Event extends EventObject
        {
            private final String cometURL;

            public Event(Oort source, String cometURL)
            {
                super(source);
                this.cometURL = cometURL;
            }

            /**
             * @return the local Oort object
             */
            public Oort getOort()
            {
                return (Oort)getSource();
            }

            /**
             * @return the URL of the comet that generated the event
             */
            public String getCometURL()
            {
                return cometURL;
            }
        }
    }

    /**
     * <p>Listener that detect when a server session is removed (means that the remote
     * comet disconnected), and disconnects the OortComet associated.</p>
     */
    private class OortCometDisconnectListener implements ServerSession.RemoveListener
    {
        private final String cometURL;
        private final String remoteOortId;

        public OortCometDisconnectListener(String cometURL, String remoteOortId)
        {
            this.cometURL = cometURL;
            this.remoteOortId = remoteOortId;
        }

        @Override
        public void removed(ServerSession session, boolean timeout)
        {
            Iterator<ServerCometInfo> cometInfos = _serverComets.values().iterator();
            while (cometInfos.hasNext())
            {
                ServerCometInfo serverCometInfo = cometInfos.next();
                if (serverCometInfo.getServerSession().getId().equals(session.getId()))
                {
                    _logger.debug("Disconnected from comet {} with session {}", cometURL, session);
                    assert remoteOortId.equals(serverCometInfo.getOortId());
                    cometInfos.remove();

                    ClientCometInfo clientCometInfo = _clientComets.remove(remoteOortId);
                    if (clientCometInfo != null)
                        clientCometInfo.getOortComet().disconnect();

                    // Do not notify if we are stopping
                    if (isRunning())
                    {
                        String remoteOortURL = serverCometInfo.getOortURL();
                        _logger.debug("Comet {} left", remoteOortURL);
                        notifyCometLeft(remoteOortURL);
                    }

                    break;
                }
            }
        }
    }

    private class OortCometLoopListener implements ServerSession.MessageListener
    {
        public boolean onMessage(ServerSession session, ServerSession sender, ServerMessage message)
        {
            // Prevent loops by not delivering a message from self or Oort session to remote Oort comets
            if (session.getId().equals(sender.getId()) || isOort(sender))
            {
                _logger.debug("{} --| {} {}", sender, session, message);
                return false;
            }
            _logger.debug("{} --> {} {}", sender, session, message);
            return true;
        }
    }

    private class HandshakeListener implements ClientSessionChannel.MessageListener
    {
        private final String cometURL;
        private final OortComet oortComet;

        private HandshakeListener(String cometURL, OortComet oortComet)
        {
            this.cometURL = cometURL;
            this.oortComet = oortComet;
        }

        public void onMessage(ClientSessionChannel channel, Message message)
        {
            OortComet comet = _pendingComets.get(cometURL);
            if (comet != null)
            {
                if (!message.isSuccessful())
                {
                    _logger.warn("Failed to connect to comet {}, message {}", cometURL, message);
                    Map<String, Object> advice = message.getAdvice();
                    if (advice != null && Message.RECONNECT_NONE_VALUE.equals(advice.get(Message.RECONNECT_FIELD)))
                    {
                        _logger.debug("Disconnecting pending comet {}", cometURL);
                        comet.disconnect();
                        // Fall through to process an eventual extension:
                        // if it was an alias URL the message will have
                        // the extension and we can map it, otherwise
                        // there will be no extension and we return
                    }
                }
            }

            Map<String,Object> ext = message.getExt();
            if (ext != null)
            {
                Object oortExtObject = ext.get(Oort.EXT_OORT_FIELD);
                if (oortExtObject instanceof Map)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> oortExt = (Map<String, Object>)oortExtObject;
                    String url = (String)oortExt.get(Oort.EXT_OORT_URL_FIELD);
                    String id = (String)oortExt.get(Oort.EXT_OORT_ID_FIELD);

                    ClientCometInfo cometInfo = new ClientCometInfo(id, url, oortComet);
                    ClientCometInfo existing = _clientComets.putIfAbsent(id, cometInfo);
                    if (existing != null)
                        cometInfo = existing;

                    if (!cometURL.equals(url))
                    {
                        cometInfo.addAliasURL(cometURL);
                        _logger.debug("Adding alias to {}: {}", url, cometURL);
                    }

                    if (message.isSuccessful())
                        _logger.debug("Connected to comet {} as {} with {}/{}", url, cometURL, message.getClientId(), oortComet.getTransport());
                }
            }

            // Remove the pending comet as last step, so that if there is a concurrent
            // call to observeComet() we are sure that we always return either the
            // pending OortComet, or the connected one from the _clientComets field
            if (message.isSuccessful() || comet != null && comet.isDisconnected())
                _pendingComets.remove(cometURL);
        }
    }

    protected static abstract class CometInfo
    {
        private final String oortId;
        private final String oortURL;

        protected CometInfo(String oortId, String oortURL)
        {
            this.oortId = oortId;
            this.oortURL = oortURL;
        }

        public String getOortId()
        {
            return oortId;
        }

        public String getOortURL()
        {
            return oortURL;
        }
    }

    protected static class ServerCometInfo extends CometInfo
    {
        private final ServerSession session;

        protected ServerCometInfo(String oortId, String oortURL, ServerSession session)
        {
            super(oortId, oortURL);
            this.session = session;
        }

        public ServerSession getServerSession()
        {
            return session;
        }
    }

    protected static class ClientCometInfo extends CometInfo
    {
        private final OortComet comet;
        private final Map<String, Boolean> urls = new ConcurrentHashMap<>();

        protected ClientCometInfo(String oortId, String oortURL, OortComet comet)
        {
            super(oortId, oortURL);
            this.comet = comet;
        }

        public OortComet getOortComet()
        {
            return comet;
        }

        public void addAliasURL(String url)
        {
            urls.put(url, Boolean.TRUE);
        }

        public boolean matchesURL(String url)
        {
            return getOortURL().equals(url) || urls.containsKey(url);
        }
    }
}
