/*
 * Copyright (c) 2008-2021 the original author or authors.
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
package org.cometd.oort.jmx;

import java.util.Set;
import java.util.TreeSet;
import org.cometd.oort.Oort;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class OortMBean extends ObjectMBean {
    private final Oort oort;

    public OortMBean(Object managedObject) {
        super(managedObject);
        this.oort = (Oort)managedObject;
    }

    // Replicated here because ConcurrentMap.KeySet is not serializable
    @ManagedAttribute(value = "Channels that are observed among Oort instances", readonly = true)
    public Set<String> getObservedChannels() {
        return new TreeSet<>(oort.getObservedChannels());
    }
}
