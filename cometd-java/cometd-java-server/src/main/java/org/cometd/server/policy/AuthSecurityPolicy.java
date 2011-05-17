package org.cometd.server.policy;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.DefaultSecurityPolicy;

/**
 * Skeleton to faciliate implementation of {@link org.cometd.bayeux.server.SecurityPolicy} based authentication.
 *
 * @author Mathieu Carbou (mathieu.carbou@gmail.com)
 */
public abstract class AuthSecurityPolicy extends DefaultSecurityPolicy {

    @Override
    public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
        return session.isLocalSession() || isAuthenticated(server, session, message);
    }

    @Override
    public boolean canCreate(BayeuxServer server, ServerSession session, String channelId, ServerMessage message) {
        return super.canCreate(server, session, channelId, message) && isAuthenticated(server, session, message);
    }

    @Override
    public boolean canPublish(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
        return super.canPublish(server, session, channel, message) && isAuthenticated(server, session, message);
    }

    @Override
    public boolean canSubscribe(BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message) {
        return super.canSubscribe(server, session, channel, message) && isAuthenticated(server, session, message);
    }

    protected abstract boolean isAuthenticated(BayeuxServer server, ServerSession session, ServerMessage message);

}
