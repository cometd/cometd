package org.cometd.bayeux.client;

import org.cometd.bayeux.BayeuxListener;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;



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
