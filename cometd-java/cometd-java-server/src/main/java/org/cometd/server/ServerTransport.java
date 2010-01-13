package org.cometd.server;

import org.cometd.common.AbstractTransport;

public class ServerTransport extends AbstractTransport
{
    final protected BayeuxServerImpl _bayeux;
    
    protected ServerTransport(BayeuxServerImpl bayeux, String name)
    {
        super(name);
        _bayeux=bayeux;
    }
    
    public interface Dispatcher
    {
        void dispatch();
        void lazyDispatch();
        void cancel();
    }
}
