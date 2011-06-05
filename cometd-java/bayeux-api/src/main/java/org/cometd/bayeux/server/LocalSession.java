package org.cometd.bayeux.server;

import org.cometd.bayeux.client.ClientSession;

/**
 * <p>A {@link LocalSession} is a {@link ClientSession} within the server.</p>
 * <p>Unlike a {@link ServerSession} that represents a remote client on the server,
 * a {@link LocalSession} is a <em>new</em> client, that is not remote and hence
 * local to the server, that lives in the server.</p>
 * <p>A {@link LocalSession} has an associated {@link ServerSession} and both share
 * the same clientId, but have distinct sets of listeners, batching state, etc.</p>
 */
public interface LocalSession extends ClientSession
{
    /**
     * @return the associated {@link ServerSession}
     */
    ServerSession getServerSession();
}
