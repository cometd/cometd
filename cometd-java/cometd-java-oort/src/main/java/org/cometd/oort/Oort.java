package org.cometd.oort;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.common.HashMapMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>Oort is the cluster manager that links one CometD server to a set of other CometD servers.</p>
 * <p>The Oort instance is created and configured by {@link OortServlet}.</p>
 * <p>This class maintains a collection of {@link OortComet} instances to each
 * CometD server, created by calls to {@link #observeComet(String)}.</p>
 * <p>The key configuration parameter is the Oort URL, which is
 * full public URL of the CometD servlet to which the Oort instance is bound,
 * for example: <code>http://myserver:8080/context/cometd</code>.</p>
 *
 * @see OortServlet
 */
public class Oort extends AbstractLifeCycle
{
    public final static String OORT_ATTRIBUTE = Oort.class.getName();
    public static final String EXT_OORT_FIELD = "org.cometd.oort";
    public static final String EXT_OORT_URL_FIELD = "oortURL";
    public static final String EXT_OORT_SECRET_FIELD = "oortSecret";
    public static final String EXT_COMET_URL_FIELD = "cometURL";
    public static final String OORT_CLOUD_CHANNEL = "/oort/cloud";
    private static final String COMET_URL_ATTRIBUTE = EXT_OORT_FIELD + "." + EXT_COMET_URL_FIELD;

    private final ConcurrentMap<String, OortComet> _knownComets = new ConcurrentHashMap<String, OortComet>();
    private final Map<String, ServerSession> _incomingComets = new ConcurrentHashMap<String, ServerSession>();
    private final ConcurrentMap<String, Boolean> _channels = new ConcurrentHashMap<String, Boolean>();
    private final Extension _oortExtension = new OortExtension();
    private ServerChannel.MessageListener _cloudListener = new CloudListener();
    private final BayeuxServer _bayeux;
    private final String _url;
    private final Logger _logger;
    private final String _secret;
    private final HttpClient _httpClient;
    private final LocalSession _oortSession;
    private boolean _clientDebugEnabled;

    public Oort(BayeuxServer bayeux, String url)
    {
        _bayeux = bayeux;
        _url = url;

        _logger = Log.getLogger(getClass().getName() + "-" + _url);
        _logger.setDebugEnabled(String.valueOf(BayeuxServerImpl.DEBUG_LOG_LEVEL).equals(bayeux.getOption(BayeuxServerImpl.LOG_LEVEL)));

        _secret = Long.toHexString(new SecureRandom().nextLong());
        _httpClient = new HttpClient();
        _oortSession = bayeux.newLocalSession("oort");
    }

    @Override
    protected void doStart() throws Exception
    {
        _httpClient.start();

        _bayeux.addExtension(_oortExtension);
        _bayeux.createIfAbsent(OORT_CLOUD_CHANNEL, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
                _cloudListener = new CloudListener();
                channel.addListener(_cloudListener);
            }
        });

        _oortSession.handshake();
    }

    @Override
    protected void doStop() throws Exception
    {
        _oortSession.disconnect();

        for (OortComet comet : _knownComets.values())
        {
            comet.disconnect();
            comet.waitFor(1000, BayeuxClient.State.DISCONNECTED);
        }
        _knownComets.clear();
        _incomingComets.clear();
        _channels.clear();

        ServerChannel oortCloudChannel = _bayeux.getChannel(OORT_CLOUD_CHANNEL);
        if (oortCloudChannel != null)
        {
            oortCloudChannel.removeListener(_cloudListener);
            oortCloudChannel.removeAuthorizer(GrantAuthorizer.GRANT_ALL);
        }
        _bayeux.removeExtension(_oortExtension);

        _httpClient.stop();
    }

    public BayeuxServer getBayeuxServer()
    {
        return _bayeux;
    }

    /**
     * @return the public absolute URL of the Oort CometD server
     */
    public String getURL()
    {
        return _url;
    }

    public String getSecret()
    {
        return _secret;
    }

    public boolean isClientDebugEnabled()
    {
        return _clientDebugEnabled;
    }

    public void setClientDebugEnabled(boolean clientDebugEnabled)
    {
        _clientDebugEnabled = clientDebugEnabled;
        for (OortComet comet : _knownComets.values())
            comet.setDebugEnabled(clientDebugEnabled);
    }

    /**
     * <p>Connects (if not already connected) and observes another CometD server
     * (identified by the given URL) via a {@link OortComet} instance.</p>
     *
     * @param cometURL the CometD url to observe
     * @return The {@link OortComet} instance associated to the CometD server identified by the URL
     */
    public OortComet observeComet(String cometURL)
    {
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

        OortComet comet = new OortComet(this, cometURL);
        OortComet existing = _knownComets.putIfAbsent(cometURL, comet);
        if (existing != null)
            return existing;

        _logger.debug("Connecting to comet {}", cometURL);
        Message.Mutable fields = HashMapMessage.parseMessages("" +
                "{" +
                "    \"" + Message.EXT_FIELD + "\": {" +
                "        \"" + EXT_OORT_FIELD + "\": {" +
                "            \"" + EXT_OORT_URL_FIELD + "\": \"" + getURL() + "\"," +
                "            \"" + EXT_OORT_SECRET_FIELD + "\": \"" + getSecret() + "\"," +
                "            \"" + EXT_COMET_URL_FIELD + "\": \"" + cometURL + "\"" +
                "        }" +
                "    }" +
                "}").get(0);
        comet.handshake(fields);
        return comet;
    }

    public OortComet deobserveComet(String cometURL)
    {
        if (_url.equals(cometURL))
            return null;

        OortComet comet = _knownComets.remove(cometURL);
        if (comet != null)
        {
            _logger.debug("Disconnecting from comet {}", cometURL);
            comet.disconnect();
        }
        return comet;
    }

    /**
     * <p>Callback method invoked when a comet joins this Oort instance and communicates
     * the other comets linked to it, so that this Oort instance can connect to those
     * comets as well.</p>
     *
     * @param comets the Oort server URLs to connect to
     */
    protected void cometsJoined(Set<String> comets)
    {
        for (String comet : comets)
        {
            if (!_url.equals(comet) && !_knownComets.containsKey(comet))
                observeComet(comet);
        }
    }

    /**
     * @return the set of known Oort comet servers URLs.
     */
    public Set<String> getKnownComets()
    {
        return new HashSet<String>(_knownComets.keySet());
    }

    /**
     * @param cometURL the URL of a Oort comet
     * @return the OortComet instance connected with the Oort comet with the given URL
     */
    public OortComet getComet(String cometURL)
    {
        return _knownComets.get(cometURL);
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
    public void observeChannel(String channelName)
    {
        ChannelId channelId = new ChannelId(channelName);
        if (channelId.isMeta() || channelId.isService())
            throw new IllegalArgumentException("Channel " + channelName + " cannot be observed because is not a broadcast channel");

        if (_channels.putIfAbsent(channelName, Boolean.TRUE) == null)
        {
            Set<String> observedChannels = getObservedChannels();
            for (OortComet comet : _knownComets.values())
                comet.subscribe(observedChannels);
        }
    }

    public void deobserveChannel(String channelId)
    {
        if (_channels.remove(channelId) != null)
        {
            for (OortComet comet : _knownComets.values())
                comet.unsubscribe(channelId);
        }
    }

    /**
     * @param session the server session to test
     * @return whether the given server session is one of those created by the Oort internal working
     */
    public boolean isOort(ServerSession session)
    {
        String id = session.getId();

        if (id.equals(_oortSession.getId()))
            return true;

        if (_incomingComets.containsKey(id))
            return true;

        for (OortComet oc : _knownComets.values())
        {
            if (id.equals(oc.getId()))
                return true;
        }

        return false;
    }

    public String toString()
    {
        return _url;
    }

    /**
     * <p>Called to register the details of a successful handshake from another Oort comet.</p>
     *
     * @param cometURL the remote Oort URL
     * @param cometSecret the remote Oort secret
     * @param session the server session that represent the connection with the remote Oort comet
     */
    protected void incomingCometHandshake(String cometURL, String cometSecret, ServerSession session)
    {
        _logger.debug("Incoming comet handshake from comet {} with {}", cometURL, session.getId());
        if (!_knownComets.containsKey(cometURL))
        {
            _logger.debug("Comet {} is unknown, establishing connection", cometURL);
            observeComet(cometURL);
        }
        else
        {
            _logger.debug("Comet {} is already known", cometURL);
        }

        session.setAttribute(COMET_URL_ATTRIBUTE, cometURL);
        _incomingComets.put(session.getId(), session);

        // Be notified when the remote comet stops
        session.addListener(new OortCometDisconnectListener(cometURL));
        // Prevent loops in sending/receiving messages
        session.addListener(new OortCometLoopListener());
    }

    /**
     * <p>Extension that detects incoming handshakes from other Oort servers.</p>
     *
     * @see Oort#incomingCometHandshake(String, String, ServerSession)
     */
    protected class OortExtension implements Extension
    {
        public boolean rcv(ServerSession from, Mutable message)
        {
            return true;
        }

        public boolean rcvMeta(ServerSession from, Mutable message)
        {
            return true;
        }

        public boolean send(ServerSession from, ServerSession to, Mutable message)
        {
            return true;
        }

        public boolean sendMeta(ServerSession to, Mutable message)
        {
            // Skip local sessions
            if (to != null && Channel.META_HANDSHAKE.equals(message.getChannel()) && message.isSuccessful())
            {
                Map<String, Object> extensionIn = message.getAssociated().getExt();
                if (extensionIn != null)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> oortExtensionIn = (Map<String, Object>)extensionIn.get(EXT_OORT_FIELD);
                    if (oortExtensionIn != null)
                    {
                        String cometURL = (String)oortExtensionIn.get(EXT_COMET_URL_FIELD);
                        if (getURL().equals(cometURL))
                        {
                            // Read incoming information
                            String remoteOortURL = (String)oortExtensionIn.get(EXT_OORT_URL_FIELD);
                            String remoteOortSecret = (String)oortExtensionIn.get(EXT_OORT_SECRET_FIELD);
                            incomingCometHandshake(remoteOortURL, remoteOortSecret, to);

                            // Send information about us
                            Map<String, Object> oortExtensionOut = new HashMap<String, Object>();
                            oortExtensionOut.put(EXT_OORT_SECRET_FIELD, getSecret());
                            Map<String, Object> extensionOut = message.getExt(true);
                            extensionOut.put(EXT_OORT_FIELD, oortExtensionOut);
                        }
                    }
                }
            }
            return true;
        }
    }

    protected void joinComets(String cometURL, Message message)
    {
        Object[] array = (Object[])message.getData();
        Set<String> comets = new HashSet<String>();
        for (Object o : array)
            comets.add(o.toString());
        _logger.debug("Received comets {} from {}", comets, cometURL);
        cometsJoined(comets);
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
        public boolean onMessage(ServerSession from, ServerChannel channel, Mutable msg)
        {
            if (!from.isLocalSession())
            {
                String cometURL = (String)from.getAttribute(COMET_URL_ATTRIBUTE);
                joinComets(cometURL, msg);
            }
            return true;
        }
    }

    public HttpClient getHttpClient()
    {
        return _httpClient;
    }

    protected Logger getLogger()
    {
        return _logger;
    }

    public Set<String> getObservedChannels()
    {
        return new HashSet<String>(_channels.keySet());
    }

    /**
     * @return the oortSession
     */
    public LocalSession getOortSession()
    {
        return _oortSession;
    }

    /**
     * <p>Listener that detect when a server session is removed (means that the remote
     * comet disconnected), and disconnects the OortComet associated.</p>
     */
    private class OortCometDisconnectListener implements ServerSession.RemoveListener
    {
        private final String cometURL;

        public OortCometDisconnectListener(String cometURL)
        {
            this.cometURL = cometURL;
        }

        public void removed(ServerSession session, boolean timeout)
        {
            ServerSession removed = _incomingComets.remove(session.getId());
            if (removed != null)
            {
                _logger.info("Disconnected from comet {} with session {}", cometURL, removed);
                OortComet oortComet = _knownComets.remove(cometURL);
                if (oortComet != null)
                    oortComet.disconnect();
            }
        }
    }

    private class OortCometLoopListener implements ServerSession.MessageListener
    {
        public boolean onMessage(ServerSession to, ServerSession from, ServerMessage message)
        {
            // Prevent loops by not delivering a message from self or Oort session to remote Oort comets
            if (to.getId().equals(from.getId()) || isOort(from))
            {
                _logger.debug("{} --| {} {}", from, to, message);
                return false;
            }
            _logger.debug("{} --> {} {}", from, to, message);
            return true;
        }
    }
}
