package org.cometd.oort;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.cometd.Channel;
import org.cometd.Client;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.cometd.client.BayeuxClient;
import org.cometd.server.MessageImpl;
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
    protected boolean _connected;
    protected boolean _handshook;

    OortComet(Oort oort,String cometUrl)
    {
        super(oort._httpClient,cometUrl,oort._timer);
        _cometUrl=cometUrl;
        _oort=oort;
        addListener(new OortCometListener());
    }

    public boolean isConnected()
    {
        return _connected;
    }

    public boolean isHandshook()
    {
        return _handshook;
    }

    @Override
    protected String extendOut(String message)
    {
        if (message==BayeuxClient.Handshake.__HANDSHAKE)
        {
            try
            {
                Message[] msg = _msgPool.parse(message);

                Map<String,Object> oort = new HashMap<String,Object>();
                oort.put("oort",_oort.getURL());
                oort.put("oortSecret",_oort.getSecret());
                oort.put("comet",_cometUrl);
                Map<String,Object> ext = msg[0].getExt(true);
                ext.put("oort",oort);

                super.extendOut(msg[0]);
                message= _msgPool.getJSON().toJSON(msg);

                for (Message m:msg)
                    if (m instanceof MessageImpl)
                        ((MessageImpl)m).decRef();

            }
            catch (IOException e)
            {
                throw new IllegalArgumentException(e);
            }
        }
        else
            message=super.extendOut(message);

        if (Log.isDebugEnabled()) Log.debug(_oort.getURL()+" ==> "+message);
        return message;
    }

    @Override
    protected void metaConnect(boolean success, Message message)
    {
        _connected=success;
        super.metaConnect(success,message);
    }

    @Override
    protected void metaHandshake(boolean success, boolean reestablish, Message message)
    {
        synchronized (_oort)
        {
            _handshook=success;
            super.metaHandshake(success,reestablish,message);
            if (success)
            {
                Map<String,Object> ext = (Map<String,Object>)message.get("ext");
                if (ext!=null)
                {
                    Map<String,Object> oort = (Map<String,Object>)ext.get("oort");
                    if (oort!=null)
                    {
                        _cometSecret=(String)oort.get("cometSecret");

                        startBatch();
                        subscribe("/oort/cloud");
                        for (String channel : _oort._channels)
                            subscribe(channel);
                        publish("/oort/cloud",_oort.getKnownComets(),_cometSecret);
                        endBatch();
                    }
                }
                if (Log.isDebugEnabled()) Log.debug(_oort.getURL()+" <== "+ext);
            }
        }
    }

    @Override
    protected void metaPublishFail(Throwable e, Message[] messages)
    {
        // TODO Auto-generated method stub
        super.metaPublishFail(e,messages);
    }


    protected class OortCometListener implements MessageListener
    {
        public void deliver(Client fromClient, Client toClient, Message msg)
        {
            String channelId = msg.getChannel();
            if (msg.getData()!=null)
            {
                if (channelId.startsWith("/oort/"))
                {
                    if (channelId.equals("/oort/cloud"))
                    {
                        Object[] data = (Object[])msg.getData();
                        Set<String> comets = new HashSet<String>();
                        for (Object o:data)
                            comets.add(o.toString());
                        _oort.observedComets(comets);
                    }

                    synchronized (_oort)
                    {
                        for( MessageListener listener : _oort._oortMessageListeners)
                            notifyMessageListener(listener, fromClient, toClient, msg);
                    }
                }
                else
                {
                    Channel channel = _oort._bayeux.getChannel(msg.getChannel(),false);
                    if (channel!=null)
                        channel.publish(_oort._oortClient,msg.getData(),msg.getId());
                }
            }
        }
    }

    private void notifyMessageListener(MessageListener listener, Client from, Client to, Message message)
    {
        try
        {
            listener.deliver(from, to, message);
        }
        catch (Throwable x)
        {
            Log.debug(x);
        }
    }
}
