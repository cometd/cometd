package org.cometd.bayeux.client;

import org.cometd.bayeux.Bayeux;

public interface ClientBayeux extends Bayeux, ClientSession
{
    void handshake();
/*
    // The semantic of the sync version is unclear:
    // does it wait infinitely until an handshake is successful, even in case of server down ?
    // Therefore I reverted the handshake() to its normal async semantic
    void handshake();

    void handshake(HandshakeCallback callback);

    interface HandshakeCallback
    {
        void handshaken(ClientSession session);
    }
*/
}
