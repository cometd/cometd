/*
 * Copyright (c) 2013 the original author or authors.
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

/**
 * A shared atomic long made of an internal {@link AtomicLong} and of an internal
 * {@link OortObject OortObject&lt;Long&gt;}.
 * <p />
 * This class exposes an API similar to that of {@link AtomicLong}, and for every successful
 * update of the local value of the internal {@link AtomicLong}, it broadcast the local value
 * via the internal {@link OortObject} to other nodes.
 * <p />
 * This class can be seen as the counterpart of {@link OortMasterCounter}.
 * <p />
 * Where the {@link OortMasterCounter} instance in each node has an internal {@link AtomicLong},
 * but only the one in the "master" node has a non-zero value, the instance of this class in
 * each node has an internal {@link AtomicLong} that has its own value.
 * <p />
 * Where in {@link OortMasterCounter} updates are always sent to the "master" node, in this
 * class updates are always local, and then broadcast to the other nodes in the cluster.
 * <p />
 * Where in {@link OortMasterCounter} each node has to send a message to the "master" node to
 * retrieve the total value, in this class the total value can be obtained from the internal
 * {@link OortObject} without network communication with other nodes.
 * <p />
 * Where {@link OortMasterCounter} trades less memory (one {@code long} per node) for
 * larger latencies (every operation on non-master nodes requires sending a message to the
 * master node), this class trades more memory (N {@code long}s per node - where N is the
 * number of nodes) for smaller latencies (operations do not require messaging).
 *
 * @see OortMasterCounter
 */
public class OortAtomicLong
{
    private final AtomicLong atomic = new AtomicLong();
    private final OortObject<Long> value;

    public OortAtomicLong(Oort oort, String name)
    {
        value = new OortObject<Long>(oort, name, OortObjectFactories.forLong());
    }

    public void start()
    {
        value.start();
    }

    public void stop()
    {
        value.stop();
    }

    public long get()
    {
        return atomic.get();
    }

    public long addAndGet(long delta)
    {
        long result = atomic.addAndGet(delta);
        value.setAndShare(result);
        return result;
    }

    public long getAndAdd(long delta)
    {
        long result = atomic.getAndAdd(delta);
        value.setAndShare(result);
        return result;
    }

    public long getAndSet(long newValue)
    {
        long result = atomic.getAndSet(newValue);
        value.setAndShare(newValue);
        return result;
    }

    public boolean compareAndSet(long expected, long newValue)
    {
        boolean result = atomic.compareAndSet(expected, newValue);
        if (result)
            value.setAndShare(newValue);
        return result;
    }

    public long sum()
    {
        return value.merge(OortObjectMergers.longSum());
    }

    protected OortObject<Long> getValue()
    {
        return value;
    }
}
