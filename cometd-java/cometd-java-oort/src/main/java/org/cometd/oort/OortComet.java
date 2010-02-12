package org.cometd.oort;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.SessionChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.util.log.Log;

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

    OortComet(Oort oort,String cometUrl)
    {
        super(oort._httpClient,cometUrl);
        _cometUrl=cometUrl;
        _oort=oort;
       
        // add extension to modify outgoing handshake with oort details
        addExtension(new Extension()
        {
            @Override
            public boolean sendMeta(ClientSession session, Mutable message)
            {
                if (Channel.META_HANDSHAKE.equals(message.getChannel()))
                {
                    Map<String,Object> oort = new HashMap<String,Object>();
                    oort.put("oort",_oort.getURL());
                    oort.put("oortSecret",_oort.getSecret());
                    oort.put("comet",_cometUrl);
                    message.getExt(true).put("oort",oort);
                    if (Log.isDebugEnabled()) 
                        Log.debug(_oort.getURL()+" ==> "+message);
                }

                return true;
            }
            
            @Override
            public boolean send(ClientSession session, Mutable message)
            {
                return true;
            }
            
            @Override
            public boolean rcvMeta(ClientSession session, Mutable message)
            {
                return true;
            }
            
            @Override
            public boolean rcv(ClientSession session, Mutable message)
            {
                return true;
            }
        });
        
        // Add listener for handshake response
        getChannel(Channel.META_HANDSHAKE).addListener(new SessionChannel.MetaChannelListener()
        {
            @Override
            public void onMetaMessage(SessionChannel channel, Message message, boolean successful, String error)
            {
                System.err.println("handshake "+message);
                
                if (successful)
                {
                    Map<String,Object> ext = message.getExt();
                    if (ext==null)
                        return;

                    Map<String,Object> oort = (Map<String,Object>)ext.get("oort");
                    if (oort==null)
                        return;
                    
                    _cometSecret=(String)oort.get("cometSecret");

                    startBatch();

                    System.err.println("Oort subscriptions for "+this);
                    
                    // subscribe to cloud notificiations
                    getChannel("/oort/cloud").subscribe(new SessionChannel.SubscriptionListener()
                    {
                        @Override
                        public void onMessage(SessionChannel channel, Message message)
                        {
                            Object[] data = (Object[])message.getData();
                            Set<String> comets = new HashSet<String>();
                            for (Object o:data)
                                comets.add(o.toString());
                            _oort.observedComets(comets);
                        }
                    });

                    for (String id : _oort._channels)
                        subscribe(id);

                    getChannel("/oort/cloud").publish(_oort.getKnownComets(),_cometSecret);
                    endBatch();
                    
                    if (Log.isDebugEnabled()) 
                        Log.debug(_oort.getURL()+" <== "+ext);
                }
            }
        });
    }

    public void subscribe(String id)
    {
        final SessionChannel channel_here = _oort._oortSession.getChannel(id);
        getChannel(id).subscribe(new SessionChannel.SubscriptionListener()
        {
            @Override
            public void onMessage(SessionChannel channel, Message message)
            {
                channel_here.publish(message.getData(),message.getId());
            }
        });
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.client.BayeuxClient#onException(java.lang.Throwable)
     */
    @Override
    public void onException(Throwable x)
    {
        Log.info(x.toString());
        Log.debug(x);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.client.BayeuxClient#onExpire()
     */
    @Override
    public void onExpire()
    {
        super.onExpire();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.client.BayeuxClient#onMessages(java.util.List)
     */
    @Override
    public void onMessages(List<Mutable> messages)
    {
        super.onMessages(messages);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.client.BayeuxClient#onProtocolError(java.lang.String)
     */
    @Override
    public void onProtocolError(String info)
    {
        Log.warn("ProtocolError: "+info);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.client.BayeuxClient#onConnectException(java.lang.Throwable)
     */
    @Override
    public void onConnectException(Throwable x)
    {
        Log.warn("ConnectException: "+x.toString());
        Log.debug(x);
    }
    

    

}
