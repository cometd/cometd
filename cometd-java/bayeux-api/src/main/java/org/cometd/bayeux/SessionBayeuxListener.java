package org.cometd.bayeux;

public interface SessionBayeuxListener extends BayeuxServer.Listener
{
    void sessionAdded(ServerSession session);
    void sessionRemoved(ServerSession session);

}
