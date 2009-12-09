package org.cometd.bayeux;

public interface SessionListener
{
    void sessionAdded(ServerSession session);
    void sessionRemoved(ServerSession session);

}
