package org.cometd.bayeux.server;

public interface SessionBayeuxListener extends BayeuxServer.Listener
{
    void sessionAdded(ServerSession session);
    void sessionRemoved(ServerSession session);

}
