package org.cometd.oort;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;

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

    final protected String _url;
    final protected String _secret;
    final protected BayeuxServer _bayeux;
    final protected HttpClient _httpClient;
    final protected Random _random=new SecureRandom();
    final protected LocalSession _oortSession;

    final protected Map<String,OortComet> _knownCommets = new ConcurrentHashMap<String,OortComet>();
    final protected Set<String> _channels = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /* ------------------------------------------------------------ */
    Oort(String id,BayeuxServer bayeux)
    {
        _url=id;
        _bayeux=bayeux;
        _secret=Long.toHexString(_random.nextLong());

        _httpClient=new HttpClient();

        _oortSession=_bayeux.newLocalSession("oort");
        bayeux.addExtension(new OortExtension());

        try
        {
            _httpClient.start();
            _oortSession.handshake();
            _oortSession.getChannel("/oort/cloud").subscribe(new RootOortClientListener());
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
     * @return The oublic absolute URL of the Oort cometd server.
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
        synchronized (this)
        {
            if (_url.equals(cometUrl))
                return null;
            OortComet comet = _knownCommets.get(cometUrl);
            if (comet==null)
            {
                try
                {
                    comet = new OortComet(this,cometUrl);
                    _knownCommets.put(cometUrl,comet);
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
            Set<String> comets = new HashSet<String>(_knownCommets.keySet());
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
        if (_channels.add(channelId))
        {
            for (OortComet comet : _knownCommets.values())
                comet.subscribe(channelId);
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isOort(ServerSession session)
    {
        LocalSession local=session.getLocalSession();
        return local==_oortSession;
    }

    /* ------------------------------------------------------------ */
    public boolean isOort(LocalSession session)
    {
        return session==_oortSession;
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
    protected void oortHandshook(String oortUrl,String oortSecret,String clientId)
    {
        Log.info(this+": "+clientId+" is oort "+oortUrl);
        if (!_knownCommets.containsKey(oortUrl))
            observeComet(oortUrl);

        ServerSession session =  _bayeux.getSession(clientId);

        session.addExtension(new RemoteOortClientExtension());
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * Extension to detect incoming handshake from other Oort servers
     * and to call {@link Oort#oortHandshook(String, String, String)}.
     *
     */
    protected class OortExtension implements Extension
    {
        @Override
        public boolean rcv(ServerSession from, Mutable message)
        {
            return true;
        }

        @Override
        public boolean rcvMeta(ServerSession from, Mutable message)
        {
            return true;
        }

        @Override
        public boolean send(ServerSession from, ServerSession to, Mutable message)
        {
            return true;
        }

        @Override
        public boolean sendMeta(ServerSession to, Mutable message)
        {
            if (message.getChannel().equals(Channel.META_HANDSHAKE) && message.isSuccessful())
            {
                Message rcv = message.getAssociated();
                if (Log.isDebugEnabled()) Log.debug(_url+" --> "+rcv);

                Map<String,Object> rcvExt = rcv.getExt();
                if (rcvExt!=null)
                {
                    Map<String,Object> oort = (Map<String,Object>)rcvExt.get("oort");
                    if (oort!=null)
                    {
                        String cometUrl = (String)oort.get("comet");
                        String oortUrl = (String)oort.get("oort");

                        if (getURL().equals(cometUrl))
                        {
                            String oortSecret = (String)oort.get("oortSecret");

                            oortHandshook(oortUrl,oortSecret,message.getClientId());

                            Object ext=message.get("ext");

                            Map<String,Object> sndExt = (Map<String,Object>)((ext instanceof JSON.Literal)?JSON.parse(ext.toString()):ext);
                            if (sndExt==null)
                                sndExt = new HashMap<String,Object>();
                            oort.put("cometSecret",getSecret());
                            sndExt.put("oort",oort);
                            message.put("ext",sndExt);
                        }
                    }
                }
                if (Log.isDebugEnabled()) Log.debug(_url+" <-- "+message);
            }
            return true;
        }
    }



    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * An Extension installed on sessions for remote Oort servers
     * that prevents publish loops.
     */
    protected class RemoteOortClientExtension implements org.cometd.bayeux.server.ServerSession.Extension
    {

        @Override
        public boolean rcv(ServerSession session, Mutable message)
        {
            return true;
        }

        @Override
        public boolean rcvMeta(ServerSession session, Mutable message)
        {
            return true;
        }

        @Override
        public ServerMessage send(ServerSession to, ServerMessage message)
        {
            // avoid loops
            boolean send = !isOort(to) || message.getChannel().startsWith("/oort/");
            return send?message:null;

        }

        @Override
        public boolean sendMeta(ServerSession session, Mutable message)
        {
            return true;
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * MessageListener that handles publishes to /oort/cloud
     */
    protected class RootOortClientListener implements ClientSessionChannel.MessageListener
    {
        @Override
        public void onMessage(ClientSessionChannel channel, Message msg)
        {
            String channelId = msg.getChannel();
            Object data=msg.getData();
            if (data instanceof Object[])
            {
                Object[] array = (Object[])msg.getData();
                Set<String> comets = new HashSet<String>();
                for (Object o:array)
                    comets.add(o.toString());
                observedComets(comets);
            }
        }

    }
}
