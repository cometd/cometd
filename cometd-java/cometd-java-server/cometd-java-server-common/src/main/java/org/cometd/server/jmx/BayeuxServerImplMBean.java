/*
 * Copyright (c) 2008-2022 the original author or authors.
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

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class BayeuxServerImplMBean extends ObjectMBean {
    public BayeuxServerImplMBean(Object managedObject) {
        super(managedObject);
    }

    private BayeuxServerImpl bayeux() {
        return (BayeuxServerImpl)getManagedObject();
    }

    @Override
    public String getObjectContextBasis() {
        return bayeux().getName();
    }

    @ManagedAttribute(value = "The number of ServerSessions", readonly = true)
    public int getSessionCount() {
        return bayeux().getSessions().size();
    }

    @ManagedAttribute(value = "The number of ServerChannels", readonly = true)
    public int getChannelCount() {
        return bayeux().getChannels().size();
    }

    // Replicated here because ConcurrentMap.KeySet is not serializable
    @ManagedAttribute(value = "The transports known by this CometD server", readonly = true)
    public Set<String> getKnownTransportNames() {
        return new TreeSet<>(bayeux().getKnownTransportNames());
    }

    // Replicated here because ConcurrentMap.KeySet is not serializable
    @ManagedAttribute(value = "The configuration option names", readonly = true)
    public Set<String> getOptionNames() {
        return new TreeSet<>(bayeux().getOptionNames());
    }

    @ManagedAttribute(value = "The information about the last sweep", readonly = true)
    public CompositeData getLastSweepInfo() {
        return toCompositeData("lastSweepInfo", bayeux().getLastSweepInfo());
    }

    @ManagedAttribute(value = "The information about the sweep that took the longest time", readonly = true)
    public CompositeData getLongestSweepInfo() {
        return toCompositeData("longestSweepInfo", bayeux().getLongestSweepInfo());
    }

    private static CompositeData toCompositeData(String typeName, Map<String, Object> map) {
        try {
            String[] itemNames = new String[map.size()];
            OpenType<?>[] itemTypes = new OpenType<?>[map.size()];
            Object[] itemValues = new Object[map.size()];
            int i = 0;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                itemNames[i] = key;
                if (value instanceof Number) {
                    itemTypes[i] = SimpleType.LONG;
                    itemValues[i] =  ((Number)value).longValue();
                } else {
                    itemTypes[i] = SimpleType.STRING;
                    itemValues[i] = String.valueOf(value);
                }
                ++i;
            }
            CompositeType type = new CompositeType(typeName, typeName, itemNames, itemNames, itemTypes);
            return new CompositeDataSupport(type, itemNames, itemValues);
        } catch (OpenDataException x) {
            throw new RuntimeException(x);
        }
    }
}
