package org.cometd.oort;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;

/**
 * Oort Comet client.
 * <p>
 * A BayeuxClient that connects the local Oort comet server to
 * a remote Oort comet server.
 *
 */
public class OortComet extends BayeuxClient
{
    protected Oort _oort;
    protected String _cometUrl;
    protected String _cometSecret;
    private volatile boolean _connected;
    private final ConcurrentMap<String, ClientSessionChannel.MessageListener> _subscriptions = new ConcurrentHashMap<String, ClientSessionChannel.MessageListener>();

    OortComet(Oort oort,String cometUrl)
    {
        super(cometUrl, LongPollingTransport.create(null, oort.getHttpClient()));
        _cometUrl=cometUrl;
        _oort=oort;
        
        _oort.getLog().info("observing {}",_cometUrl);
        
        // add extension to modify outgoing handshake with oort details
        addExtension(new Extension()
        {
            public boolean sendMeta(ClientSession session, Mutable message)
            {
                if (Channel.META_HANDSHAKE.equals(message.getChannel()))
                {
                    Map<String,Object> oort = new HashMap<String,Object>();
                    oort.put("oort",_oort.getURL());
                    oort.put("oortSecret",_oort.getSecret());
                    oort.put("comet",_cometUrl);
                    message.getExt(true).put("oort",oort);
                }

                return true;
            }

            public boolean send(ClientSession session, Mutable message)
            {
                return true;
            }

            public boolean rcvMeta(ClientSession session, Mutable message)
            {
                return true;
            }

            public boolean rcv(ClientSession session, Mutable message)
            {
                return true;
            }
        });

        // Add listener for handshake response
        getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                if (message.isSuccessful())
                {
                    _oort.getLog().info("connected {} as {}",_cometUrl,message.getClientId());
                    _connected=true;
                    Map<String,Object> ext = message.getExt();
                    if (ext==null)
                        return;

                    Map<String,Object> oort = (Map<String,Object>)ext.get("oort");
                    if (oort==null)
                        return;

                    _cometSecret=(String)oort.get("cometSecret");

                    batch(new Runnable()
                    {
                        public void run()
                        {
                            // subscribe to cloud notifications
                            getChannel("/oort/cloud").subscribe(new ClientSessionChannel.MessageListener()
                            {
                                public void onMessage(ClientSessionChannel channel, Message message)
                                {
                                    Object[] data = (Object[])message.getData();
                                    Set<String> comets = new HashSet<String>();
                                    for (Object o:data)
                                        comets.add(o.toString());
                                    _oort.observedComets(comets);
                                }
                            });
                            
                            for (String id : _oort.getObservedChannels())
                                subscribe(id);

                            getChannel("/oort/cloud").publish(_oort.getKnownComets(),_cometSecret);
                        }
                    });

                    _oort.getLog().debug("<== {}",ext);
                }
                else if (_connected)
                {
                    _connected=false;

                    _oort.getLog().warn("failed handshake {}",_cometUrl);
                }
                    
            }
        });
    }

    public void subscribe(String id)
    {
        _oort.getLog().debug("subscribe {} on {}",id,_cometUrl);
        
        ClientSessionChannel channel = getChannel(id);
        
        ClientSessionChannel.MessageListener listener = new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                _oort.getLog().debug("republish {} by {}",message,_oort.getOortSession());
                _oort.getOortSession().getChannel(message.getChannel()).publish(message.getData(),message.getId());
            }
        };
        
        if (_subscriptions.putIfAbsent(id,listener)==null)
            channel.subscribe(listener);
    }

    @Override
    public void onFailure(Throwable x, Message[] messages)
    {
        _oort.getLog().debug("onFailure {}",Arrays.asList(messages),x);
    }

    @Override
    protected void processHandshake(Mutable handshake)
    {
        super.processHandshake(handshake);
    }

    @Override
    public String toString()
    {
        return getId()+"@"+_cometUrl;
    }
    
}
