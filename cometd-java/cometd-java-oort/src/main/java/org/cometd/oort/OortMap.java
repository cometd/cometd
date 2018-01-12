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

import java.util.Collections;
import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.server.BayeuxServer;

/**
 * <p>A specialized oort object whose entity is a {@link ConcurrentMap}.</p>
 * <p>{@link OortMap} specializes {@code OortObject} and allows optimized replication of map entries
 * across the cluster: instead of replicating the whole map, that may be contain a lot of entries,
 * only entries that are modified are replicated.</p>
 * <p>Applications can use {@link #putAndShare(Object, Object, Result)} and {@link #removeAndShare(Object, Result)}
 * to broadcast changes related to single entries, as well as {@link #setAndShare(Object, Result)} to
 * change the whole map.</p>
 * <p>When a single entry is changed, {@link EntryListener}s are notified.
 * {@link DeltaListener} converts whole map updates triggered by {@link #setAndShare(Object, Result)}
 * into events for {@link EntryListener}s, giving applications a single listener type to implement
 * their business logic.</p>
 * <p>The type parameter for keys, {@code K}, must be a String to be able to use this class as-is,
 * although usage of {@link OortStringMap} is preferred.
 * This is due to the fact that a {@code Map&lt;Long,Object&gt;} containing an entry {@code {13:"foo"}}
 * is serialized in JSON as {@code {"13":"foo"}} because JSON field names must always be strings.
 * When deserialized, it is restored as a {@code Map&lt;String,Object&gt;}, which is incompatible
 * with the original type parameter for keys.
 * To overcome this issue, subclasses may override {@link #serialize(Object)} and
 * {@link #deserialize(Object)}.
 * Method {@link #serialize(Object)} should convert the entity object to a format that retains
 * enough type information for {@link #deserialize(Object)} to convert the JSON-deserialized entity
 * object that has the wrong key type to an entity object that has the right key type, like
 * {@link OortLongMap} does.</p>
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public abstract class OortMap<K, V> extends OortContainer<ConcurrentMap<K, V>> {
    private static final String TYPE_FIELD_ENTRY_VALUE = "oort.map.entry";
    private static final String ACTION_FIELD_PUT_VALUE = "oort.map.put";
    private static final String ACTION_FIELD_PUT_ABSENT_VALUE = "oort.map.put.absent";
    private static final String ACTION_FIELD_REMOVE_VALUE = "oort.map.remove";
    private static final String KEY_FIELD = "oort.map.key";
    private static final String VALUE_FIELD = "oort.map.value";

    private final List<EntryListener<K, V>> listeners = new CopyOnWriteArrayList<>();

    protected OortMap(Oort oort, String name, Factory<ConcurrentMap<K, V>> factory) {
        super(oort, name, factory);
    }

    public void addEntryListener(EntryListener<K, V> listener) {
        listeners.add(listener);
    }

    public void removeEntryListener(EntryListener<K, V> listener) {
        listeners.remove(listener);
    }

    public void removeEntryListeners() {
        listeners.clear();
    }

    /**
     * <p>Blocking version of {@link #putAndShare(Object, Object, Result)}, but deprecated.</p>
     * <p>This method will be removed in a future release.</p>
     *
     * @param key   the key to associate the value to
     * @param value the value associated with the key
     * @return the previous value associated with the key, or null if no previous value was associated with the key
     * @deprecated use {@link #putAndShare(Object, Object, Result)} instead
     */
    @Deprecated
    public V putAndShare(K key, V value) {
        Result.Deferred<V> result = new Result.Deferred<>();
        putAndShare(key, value, result);
        return result.get();
    }

    /**
     * <p>Updates a single entry of the local entity map with the given {@code key} and {@code value},
     * and broadcasts the operation to all nodes in the cluster.</p>
     * <p>Calling this method triggers notifications {@link EntryListener}s, both on this node and on remote nodes.</p>
     * <p>The entry is guaranteed to be put not when this method returns,
     * but when the {@link Result} parameter is notified.</p>
     *
     * @param key      the key to associate the value to
     * @param value    the value associated with the key
     * @param callback the callback invoked with the old value,
     *                 or {@code null} if there is no interest in the old value
     * @see #putIfAbsentAndShare(Object, Object, Result)
     * @see #removeAndShare(Object, Result)
     */
    public void putAndShare(K key, V value, Result<V> callback) {
        Map<String, Object> entry = new HashMap<>(2);
        entry.put(KEY_FIELD, key);
        entry.put(VALUE_FIELD, value);

        Data<V> data = new Data<>(6, callback);
        data.put(Info.OORT_URL_FIELD, getOort().getURL());
        data.put(Info.NAME_FIELD, getName());
        data.put(Info.OBJECT_FIELD, entry);
        data.put(Info.TYPE_FIELD, TYPE_FIELD_ENTRY_VALUE);
        data.put(Info.ACTION_FIELD, ACTION_FIELD_PUT_VALUE);

        if (logger.isDebugEnabled()) {
            logger.debug("Sharing map put {}", data);
        }
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(getChannelName()).publish(getLocalSession(), data);
    }

    /**
     * <p>Blocking version of {@link #putIfAbsentAndShare(Object, Object, Result)}, but deprecated.</p>
     * <p>This method will be removed in a future release.</p>
     *
     * @param key   the key to associate the value to
     * @param value the value associated with the key
     * @return the previous value associated with the key, or null if no previous value was associated with the key
     * @deprecated use {@link #putAndShare(Object, Object, Result)} instead
     */
    @Deprecated
    public V putIfAbsentAndShare(K key, V value) {
        Result.Deferred<V> result = new Result.Deferred<>();
        putIfAbsentAndShare(key, value, result);
        return result.get();
    }

    /**
     * <p>Updates a single entry of the local entity map with the given {@code key} and {@code value}
     * if it does not exist yet, and broadcasts the operation to all nodes in the cluster.</p>
     * <p>Calling this method triggers notifications {@link EntryListener}s, both on this node and on remote nodes,
     * only if the key did not exist.</p>
     * <p>The entry is guaranteed to be put not when this method returns,
     * but when the {@link Result} parameter is notified.</p>
     *
     * @param key      the key to associate the value to
     * @param value    the value associated with the key
     * @param callback the callback invoked with the old value,
     *                 or {@code null} if there is no interest in the old value
     * @see #putAndShare(Object, Object, Result)
     */
    public void putIfAbsentAndShare(K key, V value, Result<V> callback) {
        Map<String, Object> entry = new HashMap<>(2);
        entry.put(KEY_FIELD, key);
        entry.put(VALUE_FIELD, value);

        Data<V> data = new Data<>(6, callback);
        data.put(Info.OORT_URL_FIELD, getOort().getURL());
        data.put(Info.NAME_FIELD, getName());
        data.put(Info.OBJECT_FIELD, entry);
        data.put(Info.TYPE_FIELD, TYPE_FIELD_ENTRY_VALUE);
        data.put(Info.ACTION_FIELD, ACTION_FIELD_PUT_ABSENT_VALUE);

        if (logger.isDebugEnabled()) {
            logger.debug("Sharing map putIfAbsent {}", data);
        }
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(getChannelName()).publish(getLocalSession(), data);
    }

    /**
     * <p>Blocking version of {@link #removeAndShare(Object, Result)}, but deprecated.</p>
     * <p>This method will be removed in a future release.</p>
     *
     * @param key the key to remove
     * @return the value associated with the key, or null if no value was associated with the key
     * @deprecated use {@link #removeAndShare(Object, Result)} instead
     */
    @Deprecated
    public V removeAndShare(K key) {
        Result.Deferred<V> result = new Result.Deferred<>();
        removeAndShare(key, result);
        return result.get();
    }

    /**
     * <p>Removes the given {@code key} from the local entity map,
     * and broadcasts the operation to all nodes in the cluster.</p>
     * <p>Calling this method triggers notifications {@link EntryListener}s, both on this node and on remote nodes.</p>
     * <p>The entry is guaranteed to be removed not when this method returns,
     * but when the {@link Result} parameter is notified.</p>
     *
     * @param key      the key to remove
     * @param callback the callback invoked with the value,
     *                 or {@code null} if there is no interest in the value
     * @see #putAndShare(Object, Object, Result)
     */
    public void removeAndShare(K key, Result<V> callback) {
        Map<String, Object> entry = new HashMap<>(1);
        entry.put(KEY_FIELD, key);

        Data<V> data = new Data<>(6, callback);
        data.put(Info.OORT_URL_FIELD, getOort().getURL());
        data.put(Info.NAME_FIELD, getName());
        data.put(Info.OBJECT_FIELD, entry);
        data.put(Info.TYPE_FIELD, TYPE_FIELD_ENTRY_VALUE);
        data.put(Info.ACTION_FIELD, ACTION_FIELD_REMOVE_VALUE);

        if (logger.isDebugEnabled()) {
            logger.debug("Sharing map remove {}", data);
        }
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(getChannelName()).publish(getLocalSession(), data);
    }

    /**
     * Returns the value mapped to the given key from the local entity map of this node.
     * Differently from {@link #find(Object)}, only the local entity map is scanned.
     *
     * @param key the key mapped to the value to return
     * @return the value mapped to the given key, or
     * {@code null} if the local map does not contain the given key
     * @see #find(Object)
     */
    public V get(K key) {
        return getInfo(getOort().getURL()).getObject().get(key);
    }

    /**
     * Returns the first non-null value mapped to the given key from the entity maps of all nodes.
     * Differently from {@link #get(Object)}, entity maps of all nodes are scanned.
     *
     * @param key the key mapped to the value to return
     * @return the value mapped to the given key, or
     * {@code null} if the maps do not contain the given key
     */
    public V find(K key) {
        for (Info<ConcurrentMap<K, V>> info : this) {
            V result = info.getObject().get(key);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * @param key the key to search
     * @return the first {@link Info} whose entity map contains the given key.
     */
    public Info<ConcurrentMap<K, V>> findInfo(K key) {
        for (Info<ConcurrentMap<K, V>> info : this) {
            if (info.getObject().get(key) != null) {
                return info;
            }
        }
        return null;
    }

    @Override
    protected boolean isItemUpdate(Map<String, Object> data) {
        return TYPE_FIELD_ENTRY_VALUE.equals(data.get(Info.TYPE_FIELD));
    }

    @Override
    protected void onItem(Info<ConcurrentMap<K, V>> info, Map<String, Object> data) {
        // Retrieve entry.
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>)data.get(Info.OBJECT_FIELD);
        @SuppressWarnings("unchecked")
        final K key = (K)object.get(KEY_FIELD);
        @SuppressWarnings("unchecked")
        final V value = (V)object.get(VALUE_FIELD);

        // Perform the action.
        ConcurrentMap<K, V> map = info.getObject();
        V result;
        String action = (String)data.get(Info.ACTION_FIELD);
        switch (action) {
            case ACTION_FIELD_PUT_VALUE:
                result = map.put(key, value);
                break;
            case ACTION_FIELD_PUT_ABSENT_VALUE:
                result = map.putIfAbsent(key, value);
                break;
            case ACTION_FIELD_REMOVE_VALUE:
                result = map.remove(key);
                break;
            default:
                throw new IllegalArgumentException(action);
        }

        // Update the version.
        info.put(Info.VERSION_FIELD, data.get(Info.VERSION_FIELD));

        // Notify.
        Entry<K, V> entry = new Entry<>(key, result, value);
        if (logger.isDebugEnabled()) {
            logger.debug("{} map {} of {}", info.isLocal() ? "Local" : "Remote", action, entry);
        }
        switch (action) {
            case ACTION_FIELD_PUT_VALUE:
                notifyEntryPut(info, entry);
                break;
            case ACTION_FIELD_PUT_ABSENT_VALUE:
                if (result == null) {
                    notifyEntryPut(info, entry);
                }
                break;
            case ACTION_FIELD_REMOVE_VALUE:
                notifyEntryRemoved(info, entry);
        }

        if (data instanceof Data) {
            ((Data<V>)data).setResult(result);
        }
    }

    private void notifyEntryPut(Info<ConcurrentMap<K, V>> info, Entry<K, V> entry) {
        for (EntryListener<K, V> listener : listeners) {
            try {
                listener.onPut(info, entry);
            } catch (Throwable x) {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyEntryRemoved(Info<ConcurrentMap<K, V>> info, Entry<K, V> elements) {
        for (EntryListener<K, V> listener : listeners) {
            try {
                listener.onRemoved(info, elements);
            } catch (Throwable x) {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    /**
     * Listener for entry events that update the entity map, either locally or remotely.
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    public interface EntryListener<K, V> extends EventListener {
        /**
         * Callback method invoked after an entry is put into the entity map.
         *
         * @param info  the {@link Info} that was changed by the put
         * @param entry the entry values
         */
        public void onPut(Info<ConcurrentMap<K, V>> info, Entry<K, V> entry);

        /**
         * Callback method invoked after an entry is removed from the entity map.
         *
         * @param info  the {@link Info} that was changed by the remove
         * @param entry the entry values
         */
        public void onRemoved(Info<ConcurrentMap<K, V>> info, Entry<K, V> entry);

        /**
         * Empty implementation of {@link EntryListener}.
         *
         * @param <K> the key type
         * @param <V> the value type
         */
        public static class Adapter<K, V> implements EntryListener<K, V> {
            @Override
            public void onPut(Info<ConcurrentMap<K, V>> info, Entry<K, V> entry) {
            }

            @Override
            public void onRemoved(Info<ConcurrentMap<K, V>> info, Entry<K, V> entry) {
            }
        }
    }

    /**
     * A triple that holds the key, the previous value and the new value, used to notify entry updates:
     * <pre>
     * (key, oldValue, newValue)
     * </pre>
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    public static class Entry<K, V> {
        private final K key;
        private final V oldValue;
        private final V newValue;

        protected Entry(K key, V oldValue, V newValue) {
            this.key = key;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        /**
         * @return the key
         */
        public K getKey() {
            return key;
        }

        /**
         * @return the value before the change, may be null
         */
        public V getOldValue() {
            return oldValue;
        }

        /**
         * @return the value after the change, may be null
         */
        public V getNewValue() {
            return newValue;
        }

        @Override
        public String toString() {
            return String.format("(%s=%s->%s)", getKey(), getOldValue(), getNewValue());
        }
    }

    /**
     * <p>An implementation of {@link Listener} that converts whole map events into {@link EntryListener} events.</p>
     * <p>For example, if an entity map:</p>
     * <pre>
     * {
     *     key0: value0,
     *     key1: value1,
     *     key2: value2
     * }
     * </pre>
     * <p>is replaced by a map:</p>
     * <pre>
     * {
     *     key0: value0,
     *     key1: valueA,
     *     key3: valueB
     * }
     * </pre>
     * <p>then this listener generates two "put" events with the following {@link Entry entries}:</p>
     * <pre>
     * (key1, value1, valueA)
     * (key3, null, valueB)
     * </pre>
     * <p>and one "remove" event with the following {@link Entry entry}:</p>
     * <pre>
     * (key2, value2, null)
     * </pre>
     * <p>Note that no event is emitted for {@code key0}; the values for {@code key0} of the two
     * maps are tested via {@link Object#equals(Object)} and if they are equal no event is generated.</p>
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    public static class DeltaListener<K, V> implements Listener<ConcurrentMap<K, V>> {
        private final OortMap<K, V> oortMap;

        public DeltaListener(OortMap<K, V> oortMap) {
            this.oortMap = oortMap;
        }

        @Override
        public void onUpdated(Info<ConcurrentMap<K, V>> oldInfo, Info<ConcurrentMap<K, V>> newInfo) {
            Map<K, V> oldMap = oldInfo == null ? Collections.<K, V>emptyMap() : oldInfo.getObject();
            Map<K, V> newMap = new HashMap<>(newInfo.getObject());
            for (Map.Entry<K, V> oldEntry : oldMap.entrySet()) {
                K key = oldEntry.getKey();
                V oldValue = oldEntry.getValue();
                V newValue = newMap.remove(key);
                Entry<K, V> entry = new Entry<>(key, oldValue, newValue);
                if (newValue == null) {
                    oortMap.notifyEntryRemoved(newInfo, entry);
                } else if (!newValue.equals(oldValue)) {
                    oortMap.notifyEntryPut(newInfo, entry);
                }
            }
            for (Map.Entry<K, V> newEntry : newMap.entrySet()) {
                Entry<K, V> entry = new Entry<>(newEntry.getKey(), null, newEntry.getValue());
                oortMap.notifyEntryPut(newInfo, entry);
            }
        }

        @Override
        public void onRemoved(Info<ConcurrentMap<K, V>> info) {
            for (Map.Entry<K, V> oldEntry : info.getObject().entrySet()) {
                Entry<K, V> entry = new Entry<>(oldEntry.getKey(), oldEntry.getValue(), null);
                oortMap.notifyEntryRemoved(info, entry);
            }
        }
    }
}
