package org.cometd.server.authority;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.Authorizer.Operation;
import org.cometd.bayeux.server.Authorizer.Permission;

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
    private final boolean _canSubscribe;
    private final boolean _canPublish;
    private final boolean _canCreate;
    private final EnumSet<Authorizer.Operation> _operations;
    
    /**
     * Channel Authorizer.
     * @param operations Set of authorized operations (cannot be {@link Operation#Handshake}).
     * @param channels The authorized channels (or wildcard channel name ( see {@link ChannelId})).
     */
    public ChannelAuthorizer(final EnumSet<Authorizer.Operation> operations, String... channels)
    {
        _operations=operations;
       if (operations.contains(Operation.Handshake))
           throw new IllegalArgumentException("!Handshake");
       _canCreate=operations.contains(Operation.Create);
       _canSubscribe=operations.contains(Operation.Subscribe);
       _canPublish=operations.contains(Operation.Publish);
        for (String channel : channels)
        {
            ChannelId id = new ChannelId(channel);
            if (id.isWild())
                _wilds.add(id);
            else
                _channels.add(channel);
        }
    }
    
    public Set<String> getChannels()
    {
        Set<String> channels=new HashSet<String>(_channels);
        for (ChannelId chan:_wilds)
            channels.add(chan.toString());
        return channels;
    }
    
    public EnumSet<Authorizer.Operation> getOperations()
    {
        return EnumSet.copyOf(_operations);
    }
    
    @Override
    public void canCreate(Permission permission, BayeuxServer server, ServerSession session, ChannelId channelId, ServerMessage message)
    {
        if (_canCreate)
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
    }

    @Override
    public void canHandshake(Permission permission, BayeuxServer server, ServerSession session, ServerMessage message)
    {
    }

    @Override
    public void canPublish(Permission permission, BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message)
    {
        if (_canPublish)
        {
            if (_channels.contains(channel.getId()))
                permission.granted();
            else for (ChannelId id : _wilds)
            {
                if (id.matches(channel.getChannelId()))
                {
                    permission.granted();
                    break;
                }
            }
        }
    }

    @Override
    public void canSubscribe(Permission permission, BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message)
    {
        if (_canSubscribe)
        {
            if (_channels.contains(channel.getId()))
                permission.granted();
            else for (ChannelId id : _wilds)
            {
                if (id.matches(channel.getChannelId()))
                {
                    permission.granted();
                    break;
                }
            }
        }
    }
    
    public String toString()
    {
        return"{"+_operations+"@"+_channels+"}";
    }

}
