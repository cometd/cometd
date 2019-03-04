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
package org.cometd.oort;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

public abstract class OortContainer<T> extends OortObject<T> {
    private static final Map<String, Object> STALE_UPDATE = new HashMap<>();

    private final Map<String, Updater> updaters = new ConcurrentHashMap<>();

    public OortContainer(Oort oort, String name, Factory<T> factory) {
        super(oort, name, factory);
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
        updaters.clear();
    }

    @Override
    public void cometLeft(Event event) {
        super.cometLeft(event);
        updaters.remove(event.getCometURL());
    }

    @Override
    protected void onObject(Map<String, Object> data) {
        String oortURL = (String)data.get(Info.OORT_URL_FIELD);
        Updater updater = updater(oortURL);
        if (isItemUpdate(data)) {
            Info<T> info = getInfo(oortURL);
            if (info == null) {
                updater.enqueue(data);
                pullInfo(oortURL);
            } else {
                if (info.isLocal()) {
                    onItem(info, data);
                } else {
                    updater.enqueue(data);
                    process(info, updater);
                }
            }
        } else {
            super.onObject(data);
            Info<T> info = getInfo(oortURL);
            // Info may be null if the OortObject is stopped concurrently.
            if (info != null) {
                updater.pulling = false;
                updater.version = info.getVersion();
                process(info, updater);
            }
        }
    }

    private Updater updater(String oortURL) {
        Updater updater = updaters.get(oortURL);
        if (updater == null) {
            updater = new Updater();
            updaters.put(oortURL, updater);
        }
        return updater;
    }

    private void process(Info<T> info, Updater updater) {
        while (true) {
            Map<String, Object> data = updater.dequeue();
            if (data == null) {
                return;
            }
            if (data == STALE_UPDATE) {
                if (!updater.pulling) {
                    updater.pulling = true;
                    pullInfo(info.getOortURL());
                }
                return;
            }
            onItem(info, data);
        }
    }

    protected abstract boolean isItemUpdate(Map<String, Object> data);

    protected abstract void onItem(Info<T> info, Map<String, Object> data);

    /**
     * Item updates from other nodes may arrive out-of-order.
     * This class queues the updates, so that they can
     * be applied in the order they were generated, not
     * in the order they arrived.
     */
    private class Updater {
        private final Queue<Map<String, Object>> updates = new PriorityQueue<>(2, new VersionComparator());
        private boolean pulling;
        private long version;

        private void enqueue(Map<String, Object> data) {
            updates.offer(data);
        }

        private Map<String, Object> dequeue() {
            while (true) {
                Map<String, Object> result = updates.peek();
                if (logger.isDebugEnabled()) {
                    logger.debug("Dequeued update version={}, data={}", version, result);
                }
                if (result == null) {
                    return null;
                }
                long actual = ((Number)result.get(Info.VERSION_FIELD)).longValue();
                if (actual <= version) {
                    updates.poll();
                } else {
                    long expected = version + 1;
                    if (actual > expected) {
                        return STALE_UPDATE;
                    }
                    version = expected;
                    return updates.poll();
                }
            }
        }
    }

    private static class VersionComparator implements Comparator<Map<String, Object>> {
        @Override
        public int compare(Map<String, Object> o1, Map<String, Object> o2) {
            long v1 = ((Number)o1.get(Info.VERSION_FIELD)).longValue();
            long v2 = ((Number)o2.get(Info.VERSION_FIELD)).longValue();
            return v1 > v2 ? 1 : v1 < v2 ? -1 : 0;
        }
    }
}
