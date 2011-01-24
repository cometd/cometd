package org.cometd.oort.jmx;

import java.util.Set;
import java.util.TreeSet;

import org.cometd.oort.Oort;
import org.eclipse.jetty.jmx.ObjectMBean;

public class OortMBean extends ObjectMBean
{
    private final Oort oort;

    public OortMBean(Object managedObject)
    {
        super(managedObject);
        this.oort = (Oort)managedObject;
    }

    // Replicated here because ConcurrentMap.KeySet is not serializable
    public Set<String> getObservedChannels()
    {
        return new TreeSet<String>(oort.getObservedChannels());
    }
}
