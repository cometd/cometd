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

import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.server.LocalSession;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/**
 * <p>A shared atomic long made of an internal {@link AtomicLong} and of an internal
 * {@link OortObject OortObject&lt;Long&gt;}.</p>
 * <p>This class exposes an API similar to that of {@link AtomicLong}, and for every successful
 * update of the local value of the internal {@link AtomicLong}, it broadcast the local value
 * via the internal {@link OortObject} to other nodes.</p>
 * <p>This class can be seen as the counterpart of {@link OortPrimaryLong}.</p>
 * <p>Where the {@link OortPrimaryLong} instance in each node has an internal {@link AtomicLong},
 * but only the one in the "primary" node has a non-zero value, the instance of this class in
 * each node has an internal {@link AtomicLong} that has its own value.</p>
 * <p>Where in {@link OortPrimaryLong} updates are always sent to the "primary" node, in this
 * class updates are always local, and then broadcast to the other nodes in the cluster.</p>
 * <p>Where in {@link OortPrimaryLong} each node has to send a message to the "primary" node to
 * retrieve the total value, in this class the total value can be obtained from the internal
 * {@link OortObject} without network communication with other nodes.</p>
 * <p>Where {@link OortPrimaryLong} trades less memory (one {@code long} per node) for
 * larger latencies (every operation on non-primary nodes requires sending a message to the
 * primary node), this class trades more memory (N {@code long}s per node - where N is the
 * number of nodes) for smaller latencies (operations do not require messaging).</p>
 *
 * @see OortPrimaryLong
 */
public class OortLong extends AbstractLifeCycle {
    private final AtomicLong atomic = new AtomicLong();
    private final OortObject<Long> value;

    public OortLong(Oort oort, String name) {
        this(oort, name, 0);
    }

    /**
     * @param oort    the oort this instance is associated to
     * @param name    the name of this service
     * @param initial the initial local value
     */
    public OortLong(Oort oort, String name, long initial) {
        value = new OortObject<>(oort, name, OortObjectFactories.forLong(initial));
    }

    @Override
    protected void doStart() throws Exception {
        value.start();
    }

    @Override
    protected void doStop() throws Exception {
        value.stop();
    }

    /**
     * @return the {@link Oort} instance associated with this oort long
     */
    public Oort getOort() {
        return value.getOort();
    }

    /**
     * @return the local session that sends messages to other nodes
     */
    public LocalSession getLocalSession() {
        return value.getLocalSession();
    }

    /**
     * @param listener the listener to add that is notified of updates of the value in a node
     * @see #removeListener(OortObject.Listener)
     */
    public void addListener(OortObject.Listener<Long> listener) {
        value.addListener(listener);
    }

    /**
     * @param listener the listener to remove
     * @see #addListener(OortObject.Listener)
     */
    public void removeListener(OortObject.Listener<Long> listener) {
        value.removeListener(listener);
    }

    /**
     * Removes all listeners.
     *
     * @see #addListener(OortObject.Listener)
     * @see #removeListener(OortObject.Listener)
     */
    public void removeListeners() {
        value.removeListeners();
    }

    /**
     * @return the local value
     */
    public long get() {
        return atomic.get();
    }

    /**
     * @param delta the delta to add to the local value (may be negative)
     * @return the new local value
     */
    public long addAndGet(long delta) {
        long result = atomic.addAndGet(delta);
        value.setAndShare(result, null);
        return result;
    }

    /**
     * @param delta the delta to add to the local value (may be negative)
     * @return the old local value
     */
    public long getAndAdd(long delta) {
        long result = atomic.getAndAdd(delta);
        value.setAndShare(result, null);
        return result;
    }

    /**
     * @param newValue the new local value to set
     * @return the old local value
     */
    public long getAndSet(long newValue) {
        long result = atomic.getAndSet(newValue);
        value.setAndShare(newValue, null);
        return result;
    }

    /**
     * @param expected the expected local value
     * @param newValue the new local value
     * @return whether the operation was successful
     */
    public boolean compareAndSet(long expected, long newValue) {
        boolean result = atomic.compareAndSet(expected, newValue);
        if (result) {
            value.setAndShare(newValue, null);
        }
        return result;
    }

    /**
     * @return the sum of local values of each node
     */
    public long sum() {
        return value.merge(OortObjectMergers.longSum());
    }
}
