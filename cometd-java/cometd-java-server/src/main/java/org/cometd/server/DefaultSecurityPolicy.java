package org.cometd.server;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.SecurityPolicy;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

public class DefaultSecurityPolicy implements SecurityPolicy
{
    public boolean canCreate(BayeuxServer server, ServerSession session, String channelId, ServerMessage message)
    {
        return session!=null && session.isLocalSession() || !ChannelId.isMeta(channelId);
    }

    public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message)
    {
        return true;
    }

    public boolean canPublish(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage messsage)
    {
        return session!=null && session.isHandshook() && !channel.isMeta();
    }

    public boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage messsage)
    {
        return session!=null && session.isLocalSession() || !channel.isMeta();
    }

}
