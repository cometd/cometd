package org.cometd.server.authorizer;

import java.util.EnumSet;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.server.Authorizer;
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
    private final EnumSet<Authorizer.Operation> _operations;

    public static final GrantAuthorizer GRANT_ALL =new GrantAuthorizer(EnumSet.allOf(Operation.class));
    public static final GrantAuthorizer GRANT_PUBSUB =new GrantAuthorizer(EnumSet.of(Operation.PUBLISH,Operation.SUBSCRIBE));
    public static final GrantAuthorizer GRANT_PUB=new GrantAuthorizer(EnumSet.of(Operation.PUBLISH));
    public static final GrantAuthorizer GRANT_SUB=new GrantAuthorizer(EnumSet.of(Operation.SUBSCRIBE));
    public static final GrantAuthorizer GRANT_NONE =new GrantAuthorizer(EnumSet.noneOf(Operation.class));
    
    /**
     * Channel Authorizer.
     * @param operations Set of authorized operations.
     */
    public GrantAuthorizer(final EnumSet<Authorizer.Operation> operations)
    {
        _operations=operations;
    }

    public String toString()
    {
        return"{"+_operations+"@/**}";
    }

    public boolean appliesTo(Operation operation)
    {
        return _operations.contains(operation);
    }

    public void authorize(Permission permission, ServerSession session, Operation operation, ChannelId channelId, ServerMessage message)
    {
        permission.granted();
    }

}
