package org.cometd.bayeux;

public interface BayeuxClient extends Bayeux
{
    ClientSession handshake();
    void handshake(AsyncHandshake callback);
    
    interface AsyncHandshake
    {
        void handshook(ClientSession session);
    }
}
