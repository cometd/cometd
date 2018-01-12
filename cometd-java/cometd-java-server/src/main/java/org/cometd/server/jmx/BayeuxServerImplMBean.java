/*
 * Copyright (c) 2008-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.server.jmx;

import java.util.Set;
import java.util.TreeSet;

import org.cometd.bayeux.server.ServerChannel;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class BayeuxServerImplMBean extends ObjectMBean {
    private final BayeuxServerImpl bayeux;

    public BayeuxServerImplMBean(Object managedObject) {
        super(managedObject);
        bayeux = (BayeuxServerImpl)managedObject;
    }

    @ManagedAttribute(value = "The number of sessions", readonly = true)
    public int getSessions() {
        return bayeux.getSessions().size();
    }

    @ManagedAttribute(value = "The channel names of this server", readonly = true)
    public Set<String> getChannels() {
        Set<String> channels = new TreeSet<>();
        for (ServerChannel channel : bayeux.getChannels()) {
            channels.add(channel.getId());
        }
        return channels;
    }

    // Replicated here because ConcurrentMap.KeySet is not serializable
    @ManagedAttribute(value = "The transports known by this server", readonly = true)
    public Set<String> getKnownTransportNames() {
        return new TreeSet<>(bayeux.getKnownTransportNames());
    }

    // Replicated here because ConcurrentMap.KeySet is not serializable
    @ManagedAttribute(value = "The configuration option names", readonly = true)
    public Set<String> getOptionNames() {
        return new TreeSet<>(bayeux.getOptionNames());
    }
}
