package org.cometd.server.authority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

/**
 * Authorizer for a collection of {@link ChannelAuthorizer}s.
 * <p>
 * This {@link Authorizer} creates a more efficient data structure for looking up multiple {@link ChannelAuthorizer}s.
 * 
 */
public class ChannelsAuthorizer implements Authorizer
{
    private final List<ChannelAuthorizer> _authorizers = new ArrayList<ChannelAuthorizer>();
    private final Set<String> _createChannels = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Queue<ChannelId> _createWilds = new ConcurrentLinkedQueue<ChannelId>();
    private final Set<String> _publishChannels = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Queue<ChannelId> _publishWilds = new ConcurrentLinkedQueue<ChannelId>();
    private final Set<String> _subscribeChannels = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Queue<ChannelId> _subscribeWilds = new ConcurrentLinkedQueue<ChannelId>();
    
    /**
     */
    public ChannelsAuthorizer()
    {
    }
    
    public void addChannelAuthorizer(ChannelAuthorizer authorizer)
    {
        synchronized (_authorizers)
        {
            _authorizers.add(authorizer);
            rebuild();
        }
    }
    
    public void removeChannelAuthorizer(ChannelAuthorizer authorizer)
    {
        synchronized (_authorizers)
        {
            _authorizers.remove(authorizer);
            rebuild(); 
        }
    }
    
    private void rebuild()
    {
        // Build maps and wild lists for each operation.
        Map<Authorizer.Operation,Set<String>> channels=new HashMap<Authorizer.Operation, Set<String>>();
        Map<Authorizer.Operation,List<ChannelId>> wilds=new HashMap<Authorizer.Operation, List<ChannelId>>();
           
        for(ChannelAuthorizer a : _authorizers)
        {
            for (Authorizer.Operation op : a.getOperations())
            {

                Set<String> c = channels.get(op);
                if (c==null)
                    channels.put(op,c=new HashSet<String>());
                List<ChannelId> w = wilds.get(op);
                if (w==null)
                    wilds.put(op,w=new ArrayList<ChannelId>());

                for (String channel : a.getChannels())
                {
                    ChannelId id = new ChannelId(channel);
                    if (id.isWild())
                        w.add(id);
                    else
                        c.add(channel);
                }
            }
        }
        
        // compare with the live versions and update accordingly
        sync(_createChannels,channels.get(Operation.Create));
        sync(_createWilds,wilds.get(Operation.Create));
        sync(_subscribeChannels,channels.get(Operation.Subscribe));
        sync(_subscribeWilds,wilds.get(Operation.Subscribe));
        sync(_publishChannels,channels.get(Operation.Publish));
        sync(_publishWilds,wilds.get(Operation.Publish));
    }
    
    private <T> void sync(Collection<T> master,Collection<T>update)
    {
        if (update==null)
            master.clear();
        else
        {
            Iterator<?> iter = master.iterator();
            while(iter.hasNext())
            {
                if (!update.remove(iter.next()))
                    iter.remove();
            }   
            for (T o:update)
                master.add(o);
        }
    }
    
    @Override
    public void canCreate(Permission permission, BayeuxServer server, ServerSession session, ChannelId channelId, ServerMessage message)
    {
        if (_createChannels.contains(channelId.toString()))
            permission.granted();
        else for (ChannelId id : _createWilds)
        {
            if (id.matches(channelId))
            {
                permission.granted();
                break;
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
        if (_publishChannels.contains(channel.getId()))
            permission.granted();
        else for (ChannelId id : _publishWilds)
        {
            if (id.matches(channel.getChannelId()))
            {
                permission.granted();
                break;
            }
        }
    }

    @Override
    public void canSubscribe(Permission permission, BayeuxServer server, ServerSession session, ServerChannel channel, ServerMessage message)
    {
        if (_subscribeChannels.contains(channel.getId()))
            permission.granted();
        else for (ChannelId id : _subscribeWilds)
        {
            if (id.matches(channel.getChannelId()))
            {
                permission.granted();
                break;
            }
        }
    }
    
    public String toString()
    {
        synchronized (_authorizers)
        {
            List<Object> create = new ArrayList<Object>(_createChannels);
            create.addAll(_createWilds);
            List<Object> subscribe = new ArrayList<Object>(_subscribeChannels);
            subscribe.addAll(_subscribeWilds);
            List<Object> publish = new ArrayList<Object>(_publishChannels);
            publish.addAll(_publishWilds);
            
            return ""+create+subscribe+publish+_authorizers;
        }
    }

    public int size()
    {
        synchronized(_authorizers)
        {
            return _authorizers.size();
        }
    }

}
