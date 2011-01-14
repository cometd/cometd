package org.cometd.server.jmx;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.cometd.bayeux.server.ServerChannel;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.jmx.ObjectMBean;

public class BayeuxServerImplMBean extends ObjectMBean
{
    private BayeuxServerImpl _bayeux;
    
    public BayeuxServerImplMBean(Object managedObject)
    {
        super(managedObject);
        _bayeux=(BayeuxServerImpl)managedObject;
    }

    public int getSessions()
    {
        return _bayeux.getSessions().size();
    }
    
    public Set<String> getChannels()
    {
        Set<String> channels = new HashSet<String>();
        for (ServerChannel channel:_bayeux.getChannels())
            channels.add(channel.getId());
        return channels;
    }
    
    public List<String> getAllowedTransports()
    {
        return _bayeux.getAllowedTransports();
    }

}
