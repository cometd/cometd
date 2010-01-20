package org.cometd.oort;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Timer;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.cometd.RemoveListener;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
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
public class Oort extends AbstractLifeCycle
{
    public final static String OORT_URL = "oort.url";
    public final static String OORT_CLOUD = "oort.cloud";
    public final static String OORT_CHANNELS = "oort.channels";
    public final static String OORT_ATTRIBUTE = "org.cometd.oort.Oort";
    
    protected String _url;
    protected String _secret;
    protected Bayeux _bayeux;
    protected HttpClient _httpClient=new HttpClient();
    protected Timer _timer=new Timer();
    protected Random _random=new SecureRandom();
    protected Client _oortClient;
    protected List<MessageListener> _oortMessageListeners = new ArrayList<MessageListener>();
    
    protected Map<String,OortComet> _knownCommets = new HashMap<String,OortComet>();
    protected Set<String> _channels = new HashSet<String>();

    /* ------------------------------------------------------------ */
    Oort(String id,Bayeux bayeux)
    {
        _url=id;
        _bayeux=bayeux;
        _secret=Long.toHexString(_random.nextLong());
        
        _oortClient=_bayeux.newClient("oort");
        _oortClient.addListener(new RootOortClientListener());
        _bayeux.getChannel("/oort/cloud",true).subscribe(_oortClient);
        bayeux.addExtension(new OortExtension());
    }

    /* ------------------------------------------------------------ */
    public Bayeux getBayeux()
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
    protected void doStart() throws Exception
    {
        super.doStart();
        _httpClient.start();
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
                    comet.start();
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
                _bayeux.getChannel("/oort/cloud",true).publish(_oortClient,known,null);
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
        synchronized (this)
        {
            if (!_channels.contains(channelId))
            {
                _channels.add(channelId);
                for (OortComet comet : _knownCommets.values())
                    if (comet.isHandshook())
                        comet.subscribe(channelId);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Add a MessageListener that will receive all messages 
     * published on /oort/* channels on connected OortComets 
     * @param listener
     */
    public void addOortMessageListener(MessageListener listener)
    {
        synchronized (this)
        {
            _oortMessageListeners.add(listener);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Remove an Oort message listener.
     * @param listener
     * @return true if the listener was removed.
     */
    public boolean removeOortClientListener(MessageListener listener)
    {
        synchronized (this)
        {
            return _oortMessageListeners.remove(listener);
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isOort(Client client)
    {
        return client==_oortClient;
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
        
        Client client = _bayeux.getClient(clientId);
        
        client.addExtension(new RemoteOortClientExtension());
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
        public Message rcv(Client from, Message message)
        {
            return message;
        }

        public Message rcvMeta(Client from, Message message)
        {
            return message;
        }

        public Message send(Client from, Message message)
        {
            return message;
        }

        public Message sendMeta(Client from, Message message)
        {
            if (message.getChannel().equals(Bayeux.META_HANDSHAKE) && Boolean.TRUE.equals(message.get(Bayeux.SUCCESSFUL_FIELD)))
            {
                Message rcv = message.getAssociated();
                if (Log.isDebugEnabled()) Log.debug(_url+" --> "+rcv);
                
                Map<String,Object> rcvExt = (Map<String,Object>)rcv.get("ext");
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
            return message;
        }   
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * An Extension installed on clients for remote Oort servers
     * that prevents publish loops.
     */
    protected class RemoteOortClientExtension implements Extension
    {
        public boolean queueMaxed(Client from, Client client, Message message)
        {
            // avoid loops
            boolean send = from!=_oortClient || message.getChannel().startsWith("/oort/");
            return send;
        }

        public Message rcv(Client from, Message message)
        {
            return message;
        }

        public Message rcvMeta(Client from, Message message)
        {
            return message;
        }

        public Message send(Client from, Message message)
        {
            // avoid loops
            boolean send = !isOort(from) || message.getChannel().startsWith("/oort/");
            return send?message:null;
        }

        public Message sendMeta(Client from, Message message)
        {
            return message;
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * MessageListener that handles publishes to /oort/cloud
     */
    protected class RootOortClientListener implements RemoveListener, MessageListener
    {
        public void removed(String clientId, boolean timeout)
        {
            // TODO
        }

        public void deliver(Client fromClient, Client toClient, Message msg)
        {
            String channelId = msg.getChannel();
            if (msg.getData()!=null)
            {
                if (channelId.equals("/oort/cloud") && msg.getData() instanceof Object[])
                {
                    Object[] data = (Object[])msg.getData();
                    Set<String> comets = new HashSet<String>();
                    for (Object o:data)
                        comets.add(o.toString());
                    observedComets(comets);
                }   
            }
        }
        
    }
}
