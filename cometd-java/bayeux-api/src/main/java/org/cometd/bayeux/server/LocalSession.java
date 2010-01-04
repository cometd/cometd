package org.cometd.bayeux.server;

import org.cometd.bayeux.client.ClientSession;


public interface LocalSession extends ClientSession
{
    ServerSession getServerSession();
}
