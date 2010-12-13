package org.cometd.oort;

import java.net.URI;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * Oort cluster of cometd servers.
 * <p>
 * This class maintains a collection of {@link OortComet} instances to each
 * comet server identified by calls to {@link #observeComet(String)}. The Oort
 * instance is created and configured by {@link OortServlet}.
 * <p>
 * The key configuration parameter that must be set is the Oort URL, which is
 * full public URL to the cometd servlet, eg. http://myserver:8080/context/cometd
 * See {@link OortServlet} for more configuration detail.<p>
 * @author gregw
 *
 */
public class Oort
{
    public final static String OORT_URL = "oort.url";
    public final static String OORT_CLOUD = "oort.cloud";
    public final static String OORT_CHANNELS = "oort.channels";
    public final static String OORT_ATTRIBUTE = "org.cometd.oort.Oort";

    private final String _url;
    private final String _secret;
    private final BayeuxServer _bayeux;
    private final HttpClient _httpClient;
    private final Random _random=new SecureRandom();
    private final LocalSession _oortSession;
    private final Logger _log;
    private final Map<String,OortComet> _knownComets = new ConcurrentHashMap<String,OortComet>();
    private final Map<String,ServerSession> _incomingComets = new ConcurrentHashMap<String,ServerSession>();
    private final ConcurrentMap<String, Boolean> _channels = new ConcurrentHashMap<String, Boolean>();

    /* ------------------------------------------------------------ */
    Oort(String url,BayeuxServer bayeux)
    {
        _url=url;
        _bayeux=bayeux;
        _secret=Long.toHexString(_random.nextLong());
        _log=org.eclipse.jetty.util.log.Log.getLogger("Oort-"+_url);
        if (Boolean.valueOf(String.valueOf(bayeux.getOption("debug"))))
            _log.setDebugEnabled(true);

        _httpClient=new HttpClient();

        _oortSession=_bayeux.newLocalSession("oort");
        bayeux.addExtension(new OortExtension());

        bayeux.createIfAbsent("/oort/cloud", new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
                channel.addListener(new RootCloudListener());
            }
        });
        
        try
        {
            _httpClient.start();
            _oortSession.handshake();
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    public BayeuxServer getBayeux()
    {
        return _bayeux;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The public absolute URL of the Oort cometd server.
     */
    public String getURL()
    {
        return _url;
    }

    /* ------------------------------------------------------------ */
    public String getSecret()
    {
        return _secret;
    }

    /* ------------------------------------------------------------ */
    /**
     * Observe an Oort Comet server.
     * <p>
     * The the comet server is not already observed, start a {@link OortComet}
     * instance for it.
     *
     * @param cometUrl
     * @return The {@link OortComet} instance for the comet server.
     */
    public OortComet observeComet(String cometUrl)
    {
        try
        {
            URI uri = new URI(cometUrl);
            
            if (uri.getScheme()==null || uri.getHost()==null)
            {
                _log.warn("No protocol|host in comet URL: "+cometUrl);
                return null;
            }
        }
        catch(Exception e)
        {
            _log.debug(e);
            _log.warn("Bad comet URL: "+cometUrl);
            return null;
        }
        
        synchronized (this)
        {
            if (_url.equals(cometUrl))
                return null;
            OortComet comet = _knownComets.get(cometUrl);
            if (comet==null)
            {
                try
                {
                    comet = new OortComet(this,cometUrl);
                    _knownComets.put(cometUrl,comet);
                    comet.handshake();
                }
                catch(Exception e)
                {
                    throw new IllegalStateException(e);
                }
            }
            return comet;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Pass observed comets.
     * <p>
     * Called when another comet server publishes it's list of
     * known comets to the /oort/cloud channel.  If the list contains
     * any unknown commets, then {@link #observeComet(String)} is
     * called for each.
     * @param comets
     */
    void observedComets(Set<String> comets)
    {
        synchronized (this)
        {
            Set<String> known=getKnownComets();
            for (String comet : comets)
                if (!_url.equals(comet))
                    observeComet(comet);
            known=getKnownComets();

            if (!comets.containsAll(known))
                _bayeux.getChannel("/oort/cloud").publish(_oortSession,known,null);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The set of known Oort comet servers URLs.
     */
    public Set<String> getKnownComets()
    {
        synchronized (this)
        {
            Set<String> comets = new HashSet<String>(_knownComets.keySet());
            comets.add(_url);
            return comets;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Observer a channel.
     * <p>
     * Once observed, all {@link OortComet} instances subscribe
     * to the channel and will repeat any messages published to
     * the local channel (with loop prevention), so that the
     * messages are distributed to all Oort comet servers.
     * @param channelId
     */
    public void observeChannel(String channelId)
    {
        if (_channels.putIfAbsent(channelId, Boolean.TRUE) == null)
        {
            for (OortComet comet : _knownComets.values())
                comet.subscribe();
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isOort(ServerSession session)
    {
        String id=session.getId();

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


    /* ------------------------------------------------------------ */
    public String toString()
    {
        return _url;
    }

    /* ------------------------------------------------------------ */
    /**
     * Called to register the details of a successful handshake with an
     * Oort comet.  A {@link RemoteOortClientListener} instance is added to
     * the local Oort client instance.
     * @param oortUrl
     * @param oortSecret
     * @param clientId
     */
    protected void incomingCometHandshake(String oortUrl,String oortSecret,String clientId)
    {
        _log.info("incoming {}@{}",clientId,oortUrl);
        if (!_knownComets.containsKey(oortUrl))
            observeComet(oortUrl);

        ServerSession session =  _bayeux.getSession(clientId);
        session.addListener(new ServerSession.MessageListener()
        {
            public boolean onMessage(ServerSession to, ServerSession from, ServerMessage message)
            {
                // Prevent loops by not delivering a message from self or Oort session to the remote comet
                if (to==from || to.getId().equals(from.getId()) || isOort(from))
                {
                    _log.debug("{} ---| {} {}",from,to,message);
                    return false;
                }
                _log.debug("{} ---> {} {}",from,to,message);
                return true;
            }
        });
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * Extension to detect incoming handshake from other Oort servers
     * and to call {@link Oort#incomingCometHandshake(String, String, String)}.
     *
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
            if (message.getChannel().equals(Channel.META_HANDSHAKE) && message.isSuccessful())
            {
                Message rcv = message.getAssociated();

                Map<String,Object> rcvExt = rcv.getExt();
                if (rcvExt!=null)
                {
                    Map<String,Object> oort_ext = (Map<String,Object>)rcvExt.get("oort");
                    if (oort_ext!=null)
                    {
                        String cometUrl = (String)oort_ext.get("comet");
                        String oortUrl = (String)oort_ext.get("oort");

                        if (getURL().equals(cometUrl))
                        {
                            String oortSecret = (String)oort_ext.get("oortSecret");

                            incomingCometHandshake(oortUrl,oortSecret,message.getClientId());

                            Object ext=message.get("ext");

                            Map<String,Object> sndExt = (Map<String,Object>)((ext instanceof JSON.Literal)?JSON.parse(ext.toString()):ext);
                            if (sndExt==null)
                                sndExt = new HashMap<String,Object>();
                            oort_ext.put("cometSecret",getSecret());
                            sndExt.put("oort",oort_ext);
                            message.put("ext",sndExt);
                        }
                    }
                }
            }
            return true;
        }
    }




    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * MessageListener that handles publishes to /oort/cloud
     */
    protected class RootCloudListener implements ServerChannel.MessageListener, ServerChannel.SubscriptionListener
    {
        public boolean onMessage(ServerSession from, ServerChannel channel, Mutable msg)
        {
            Object data=msg.getData();
            if (data instanceof Object[])
            {
                Object[] array = (Object[])msg.getData();
                Set<String> comets = new HashSet<String>();
                for (Object o:array)
                    comets.add(o.toString());
                observedComets(comets);
            }
            return true;
        }

        public void subscribed(ServerSession session, ServerChannel channel)
        {
            _log.info("/oort/cloud subscribe {}",session.getId());
            _incomingComets.put(session.getId(),session);
        }

        public void unsubscribed(ServerSession session, ServerChannel channel)
        {
            _log.info("/oort/cloud unsubscribe {}",session.getId());
            _incomingComets.remove(session.getId());
        }
    }

    public HttpClient getHttpClient()
    {
        return _httpClient;
    }

    public Logger getLog()
    {
        return _log;
    }

    public Set<String> getObservedChannels()
    {
        return _channels.keySet();
    }

    /**
     * @return the oortSession
     */
    public LocalSession getOortSession()
    {
        return _oortSession;
    }
}
