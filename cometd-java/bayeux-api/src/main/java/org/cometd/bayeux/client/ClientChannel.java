package org.cometd.bayeux.client;

import java.util.Set;

import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;



/* ------------------------------------------------------------ */
/** Server side representation of a Bayeux Channel/
 *
 */
public interface ClientChannel extends Channel
{

    void addListener(ClientChannelListener listener);
    
    void removeListener(ClientChannelListener listener);
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     */
    interface ClientChannelListener extends BayeuxListener
    {}

    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    interface MetaListener extends ClientChannelListener
    {
        void onMetaMessage(BayeuxClient bayeux, ClientChannel channel, Message message, boolean successful, String error);
    }
}
