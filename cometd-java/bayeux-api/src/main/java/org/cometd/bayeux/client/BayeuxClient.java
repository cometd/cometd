package org.cometd.bayeux.client;

import org.cometd.bayeux.Bayeux;

public interface BayeuxClient extends Bayeux
{
    ClientSession handshake();
    void handshake(AsyncHandshake callback);
    
    interface AsyncHandshake
    {
        void handshook(ClientSession session);
    }
}
