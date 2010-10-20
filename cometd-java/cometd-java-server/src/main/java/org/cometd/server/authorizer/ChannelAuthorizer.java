package org.cometd.server.authorizer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.Authorizer.Operation;
import org.cometd.bayeux.server.Authorizer.Permission;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

/**
 * Channel Authorizer implementation.
 * <p>
 * This convenience implementation of {@link Authorizer}, will grant
 * permission to Create, Subscribe and/or publish to a set of channels (which may be wildcards).
 * This authorizer will never deny permission, nor will it grant or deny a Handshake operation.
 *
 */
public class ChannelAuthorizer implements Authorizer
{
    private final Set<String> _channels = new HashSet<String>();
    private final List<ChannelId> _wilds = new ArrayList<ChannelId>();
    private final EnumSet<Authorizer.Operation> _operations;

    /**
     * Channel Authorizer.
     * @param operations Set of authorized operations (cannot be {@link Operation#HANDSHAKE}).
     * @param channels The authorized channels (or wildcard channel name ( see {@link ChannelId})).
     */
    public ChannelAuthorizer(final EnumSet<Authorizer.Operation> operations, String... channels)
    {
        _operations=operations;
       if (operations.contains(Operation.HANDSHAKE))
           throw new IllegalArgumentException("!Handshake");
        for (String channel : channels)
        {
            ChannelId id = new ChannelId(channel);
            if (id.isWild())
                _wilds.add(id);
            else
                _channels.add(channel);
        }
    }
    
    public boolean appliesTo(Operation operation)
    {
        return _operations.contains(operation);
    }

    public void authorize(Permission permission, BayeuxServer server, ServerSession session, Operation operation, ChannelId channelId, ServerMessage message)
    {
        if (_channels.contains(channelId.toString()))
            permission.granted();
        else for (ChannelId id : _wilds)
        {
            if (id.matches(channelId))
            {
                permission.granted();
                break;
            }
        }
    }

    public Set<String> getChannels()
    {
        Set<String> channels=new HashSet<String>(_channels);
        for (ChannelId chan:_wilds)
            channels.add(chan.toString());
        return channels;
    }
    
    public String toString()
    {
        return"{"+_operations+"@"+_channels+"}";
    }

}
