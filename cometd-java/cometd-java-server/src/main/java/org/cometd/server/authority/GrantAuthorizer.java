package org.cometd.server.authority;

import java.util.EnumSet;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

/**
 * Grant Authorizer implementation.
 * <p>
 * This convenience implementation of {@link Authorizer}, will grant
 * permission to a set of operations.
 *
 */
public class GrantAuthorizer implements Authorizer
{
    private final boolean _canHandshake;
    private final boolean _canSubscribe;
    private final boolean _canPublish;
    private final boolean _canCreate;
    private final EnumSet<Authorizer.Operation> _operations;


    /**
     * Channel Authorizer.
     * @param operations Set of authorized operations.
     */
    public GrantAuthorizer(final EnumSet<Authorizer.Operation> operations)
    {
        _operations=operations;
        _canHandshake=operations.contains(Operation.Handshake);
       _canCreate=operations.contains(Operation.Create);
       _canSubscribe=operations.contains(Operation.Subscribe);
       _canPublish=operations.contains(Operation.Publish);
    }

    public void canCreate(Permission permission, BayeuxServer server, ServerSession session, ChannelId channelId, ServerMessage message)
    {
        if (_canCreate)
            permission.granted();
    }

    public void canHandshake(Permission permission, BayeuxServer server, ServerSession session, ServerMessage message)
    {
        if (_canHandshake)
            permission.granted();
    }

    public void canPublish(Permission permission, BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message)
    {
        if (_canPublish)
            permission.granted();
    }

    public void canSubscribe(Permission permission, BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message)
    {
        if (_canSubscribe)
            permission.granted();
    }

    public String toString()
    {
        return"{"+_operations+"@/**}";
    }

}
