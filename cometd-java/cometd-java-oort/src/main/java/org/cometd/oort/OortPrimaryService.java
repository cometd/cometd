/*
 * Copyright (c) 2008-2019 the original author or authors.
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
package org.cometd.oort;

/**
 * <p>{@link OortPrimaryService} extends {@link OortService} to allow applications
 * to perform actions on entities that always live in a "primary" node.</p>
 * <p>Applications may have entities that are naturally owned by any node. For
 * example, in a chat application a chat room may be created by a user in
 * any node, and be owned by the node the user that created it is connected to.</p>
 * <p>There are cases, however, where entities cannot be owned by any node, but
 * instead must be owned by one node only, usually referred to as the "primary"
 * node.</p>
 * <p>A typical example of such an entity is a unique (across the cluster) ID
 * generator that produces IDs in the form of {@code long} - the primitive Java
 * type - values, or a service that accesses a storage (such as a file system)
 * that is only available on a particular node, etc.</p>
 * <p>{@link OortPrimaryService} makes easier to write services that perform actions
 * on entities that must be owned by a single node only.
 * There is one instance of {@link OortPrimaryService} with the same name for each
 * node, but only one of them is the "primary".
 * Then, applications may call {@link #getPrimaryOortURL()} to get the Oort URL
 * of the "primary" node, and pass that Oort URL to
 * {@link #forward(String, Object, Object)} as described in {@link OortService}.</p>
 *
 * @param <R> the result type
 * @param <C> the opaque context type
 */
public abstract class OortPrimaryService<R, C> extends OortService<R, C> {
    private final boolean primary;
    private final OortObject<Boolean> nodes;

    /**
     * @param oort   the oort this instance is associated to
     * @param name   the name of this service
     * @param primary whether this service lives on the "primary" node
     */
    public OortPrimaryService(Oort oort, String name, boolean primary) {
        super(oort, name);
        this.primary = primary;
        this.nodes = new OortObject<>(oort, name, OortObjectFactories.forBoolean(primary));
    }

    /**
     * @return whether this node is the "primary" node
     */
    public boolean isPrimary() {
        return primary;
    }

    /**
     * @return the "primary" Oort URL, or null if the "primary" node is down.
     */
    public String getPrimaryOortURL() {
        OortObject.Info<Boolean> info = nodes.getInfoByObject(true);
        return info == null ? null : info.getOortURL();
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        nodes.start();
    }

    @Override
    protected void doStop() throws Exception {
        nodes.stop();
        super.doStop();
    }
}
