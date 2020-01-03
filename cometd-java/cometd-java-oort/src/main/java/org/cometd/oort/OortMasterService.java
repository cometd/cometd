/*
 * Copyright (c) 2008-2020 the original author or authors.
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
 * <p>{@link OortMasterService} extends {@link OortService} to allow applications
 * to perform actions on entities that always live in a "master" node.</p>
 * <p>Applications may have entities that are naturally owned by any node. For
 * example, in a chat application a chat room may be created by a user in
 * any node, and be owned by the node the user that created it is connected to.</p>
 * <p>There are cases, however, where entities cannot be owned by any node, but
 * instead must be owned by one node only, usually referred to as the "master"
 * node.</p>
 * <p>A typical example of such an entity is a unique (across the cluster) ID
 * generator that produces IDs in the form of {@code long} - the primitive Java
 * type - values, or a service that accesses a storage (such as a file system)
 * that is only available on a particular node, etc.</p>
 * <p>{@link OortMasterService} makes easier to write services that perform actions
 * on entities that must be owned by a single node only.
 * There is one instance of {@link OortMasterService} with the same name for each
 * node, but only one of them is the "master".
 * Then, applications may call {@link #getMasterOortURL()} to get the Oort URL
 * of the "master" node, and pass that Oort URL to
 * {@link #forward(String, Object, Object)} as described in {@link OortService}.</p>
 *
 * @param <R> the result type
 * @param <C> the opaque context type
 */
public abstract class OortMasterService<R, C> extends OortService<R, C> {
    private final boolean master;
    private final OortObject<Boolean> nodes;

    /**
     * @param oort   the oort this instance is associated to
     * @param name   the name of this service
     * @param master whether this service lives on the "master" node
     */
    public OortMasterService(Oort oort, String name, boolean master) {
        super(oort, name);
        this.master = master;
        this.nodes = new OortObject<>(oort, name, OortObjectFactories.forBoolean(master));
    }

    /**
     * @return whether this node is the "master" node
     */
    public boolean isMaster() {
        return master;
    }

    /**
     * @return the "master" Oort URL, or null if the "master" node is down.
     */
    public String getMasterOortURL() {
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
