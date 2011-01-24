package org.cometd.server.jmx;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.cometd.bayeux.server.ServerChannel;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.jmx.ObjectMBean;

public class BayeuxServerImplMBean extends ObjectMBean
{
    private final BayeuxServerImpl bayeux;

    public BayeuxServerImplMBean(Object managedObject)
    {
        super(managedObject);
        bayeux = (BayeuxServerImpl)managedObject;
    }

    public int getSessions()
    {
        return bayeux.getSessions().size();
    }

    public Set<String> getChannels()
    {
        Set<String> channels = new TreeSet<String>();
        for (ServerChannel channel : bayeux.getChannels())
            channels.add(channel.getId());
        return channels;
    }

    // Replicated here to avoid a mismatch between getter and setter
    public List<String> getAllowedTransports()
    {
        return bayeux.getAllowedTransports();
    }

    // Replicated here because ConcurrentMap.KeySet is not serializable
    public Set<String> getKnownTransportNames()
    {
        return new TreeSet<String>(bayeux.getKnownTransportNames());
    }
}
