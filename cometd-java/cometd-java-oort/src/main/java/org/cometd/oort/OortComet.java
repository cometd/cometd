package org.cometd.oort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;
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
        super(cometUrl, LongPollingTransport.create(null, oort._httpClient));
        _cometUrl=cometUrl;
        _oort=oort;

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
                    if (Log.isDebugEnabled())
                        Log.debug(_oort.getURL()+" ==> "+message);
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
                System.err.println("handshake "+message);

                if (message.isSuccessful())
                {
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
                            System.err.println("Oort subscriptions for "+this);

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

                            for (String id : _oort._channels.keySet())
                                subscribe(id);

                            getChannel("/oort/cloud").publish(_oort.getKnownComets(),_cometSecret);
                        }
                    });

                    if (Log.isDebugEnabled())
                        Log.debug(_oort.getURL()+" <== "+ext);
                }
            }
        });
    }

    public void subscribe(String id)
    {
        final ClientSessionChannel channel_here = _oort._oortSession.getChannel(id);
        getChannel(id).subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                channel_here.publish(message.getData(),message.getId());
            }
        });
    }
}
