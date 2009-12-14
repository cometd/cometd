package org.cometd.bayeux.client;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Channel;

public interface BayeuxClient extends Bayeux
{
    MetaChannel getMetaChannel(Channel.MetaType meta);
    
    ClientSession handshake();
    
    void handshake(AsyncHandshake callback);
    
    interface AsyncHandshake
    {
        void handshook(ClientSession session);
    }
}
