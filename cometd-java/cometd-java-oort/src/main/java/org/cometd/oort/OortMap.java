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

import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.common.MarkedReference;

/**
 *
 * @param <K>
 * @param <V>
 */
public class OortMap<K, V> extends OortObject<ConcurrentMap<K, V>>
{
    public static final String TYPE_FIELD_ENTRY_VALUE = "oort.map.entry";
    public static final String ACTION_FIELD_PUT_VALUE = "oort.map.put";
    public static final String ACTION_FIELD_REMOVE_VALUE = "oort.map.remove";
    private static final String KEY_FIELD = "oort.map.key";
    private static final String VALUE_FIELD = "oort.map.value";

    private final List<EntryListener<K, V>> listeners = new CopyOnWriteArrayList<EntryListener<K, V>>();

    public OortMap(Oort oort, String name, Factory<ConcurrentMap<K, V>> factory)
    {
        super(oort, name, factory);
    }

    public void addEntryListener(EntryListener<K, V> listener)
    {
        listeners.add(listener);
    }

    public void removeEntryListener(EntryListener<K, V> listener)
    {
        listeners.remove(listener);
    }

    public V putAndShare(K key, V value)
    {
        Map<String, Object> entry = new HashMap<String, Object>(2);
        entry.put(KEY_FIELD, key);
        entry.put(VALUE_FIELD, value);

        Data data = new Data(6);
        data.put(Info.ID_FIELD, nextId());
        data.put(Info.OORT_URL_FIELD, getOort().getURL());
        data.put(Info.NAME_FIELD, getName());
        data.put(Info.OBJECT_FIELD, entry);
        data.put(Info.TYPE_FIELD, TYPE_FIELD_ENTRY_VALUE);
        data.put(Info.ACTION_FIELD, ACTION_FIELD_PUT_VALUE);

        logger.debug("Sharing map put {}", data);
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(getChannelName()).publish(getLocalSession(), data, null);

        return (V)data.getResult();
    }

    public V removeAndShare(K key)
    {
        Map<String, Object> entry = new HashMap<String, Object>(1);
        entry.put(KEY_FIELD, key);

        Data data = new Data(6);
        data.put(Info.ID_FIELD, nextId());
        data.put(Info.OORT_URL_FIELD, getOort().getURL());
        data.put(Info.NAME_FIELD, getName());
        data.put(Info.OBJECT_FIELD, entry);
        data.put(Info.TYPE_FIELD, TYPE_FIELD_ENTRY_VALUE);
        data.put(Info.ACTION_FIELD, ACTION_FIELD_REMOVE_VALUE);

        logger.debug("Sharing map remove {}", data);
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(getChannelName()).publish(getLocalSession(), data, null);

        return (V)data.getResult();
    }

    /**
     * Returns the value mapped to the given key from the local map.
     *
     * @param key the key mapped to the value to return
     * @return the value mapped to the given key, or
     *         {@code null} if the local map does not contain the given key
     * @see #find(Object)
     */
    public V get(K key)
    {
        return getInfo(getOort().getURL()).getObject().get(key);
    }

    /**
     * Returns the first non-null value mapped to the given key from all the maps.
     * @param key the key mapped to the value to return
     * @return the value mapped to the given key, or
     *         {@code null} if the maps do not contain the given key
     */
    public V find(K key)
    {
        for (Info<ConcurrentMap<K, V>> info : this)
        {
            V result = info.getObject().get(key);
            if (result != null)
                return result;
        }
        return null;
    }

    /**
     * Returns the first {@link Info} whose map contains the given key.
     *
     * @param key the key
     * @return
     */
    public Info<ConcurrentMap<K, V>> findInfo(K key)
    {
        for (Info<ConcurrentMap<K, V>> info : this)
        {
            if (info.getObject().get(key) != null)
                return info;
        }
        return null;
    }

    @Override
    protected void onObject(Map<String, Object> data)
    {
        if (TYPE_FIELD_ENTRY_VALUE.equals(data.get(Info.TYPE_FIELD)))
        {
            String action = (String)data.get(Info.ACTION_FIELD);
            final boolean remove = ACTION_FIELD_REMOVE_VALUE.equals(action);
            if (!ACTION_FIELD_PUT_VALUE.equals(action) && !remove)
                throw new IllegalArgumentException(action);

            String oortURL = (String)data.get(Info.OORT_URL_FIELD);
            Info<ConcurrentMap<K, V>> info = getInfo(oortURL);
            if (info != null)
            {
                // Retrieve entry
                @SuppressWarnings("unchecked")
                Map<String, Object> object = (Map<String, Object>)data.get(Info.OBJECT_FIELD);
                @SuppressWarnings("unchecked")
                final K key = (K)object.get(KEY_FIELD);
                @SuppressWarnings("unchecked")
                final V value = (V)object.get(VALUE_FIELD);

                // Set the new Info
                Info<ConcurrentMap<K, V>> newInfo = new Info<ConcurrentMap<K, V>>(getOort().getURL(), data);
                final ConcurrentMap<K, V> map = info.getObject();
                newInfo.put(Info.OBJECT_FIELD, map);
                final AtomicReference<V> result = new AtomicReference<V>();
                MarkedReference<Info<ConcurrentMap<K, V>>> old = setInfo(newInfo, new Runnable()
                {
                    public void run()
                    {
                        if (remove)
                            result.set(map.remove(key));
                        else
                            result.set(map.put(key, value));
                    }
                });

                Entry<K, V> entry = new Entry<K, V>(key, result.get(), value);

                logger.debug("{} {} map {} of {}",
                        old.isMarked() ? "Performed" : "Skipped",
                        newInfo.isLocal() ? "local" : "remote",
                        remove ? "remove" : "put",
                        entry);

                if (old.isMarked())
                {
                    if (remove)
                        notifyEntryRemoved(info, entry);
                    else
                        notifyEntryPut(info, entry);
                }

                if (data instanceof Data)
                    ((Data)data).setResult(result.get());
            }
            else
            {
                logger.debug("No info for {}", oortURL);
            }
        }
        else
        {
            super.onObject(data);
        }
    }

    private void notifyEntryPut(Info<ConcurrentMap<K, V>> info, Entry<K, V> entry)
    {
        for (EntryListener<K, V> listener : listeners)
        {
            try
            {
                listener.onPut(info, entry);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyEntryRemoved(Info<ConcurrentMap<K, V>> info, Entry<K, V> elements)
    {
        for (EntryListener<K, V> listener : listeners)
        {
            try
            {
                listener.onRemoved(info, elements);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    public interface EntryListener<K, V> extends EventListener
    {
        public void onPut(Info<ConcurrentMap<K, V>> info, Entry<K, V> entry);

        public void onRemoved(Info<ConcurrentMap<K, V>> info, Entry<K, V> entry);

        public static class Adapter<K, V> implements EntryListener<K, V>
        {
            public void onPut(Info<ConcurrentMap<K, V>> info, Entry<K, V> entry)
            {
            }

            public void onRemoved(Info<ConcurrentMap<K, V>> info, Entry<K, V> entry)
            {
            }
        }
    }

    public static class DeltaListener<K, V> implements Listener<ConcurrentMap<K, V>>
    {
        private final OortMap<K, V> oortMap;

        public DeltaListener(OortMap<K, V> oortMap)
        {
            this.oortMap = oortMap;
        }

        public void onUpdated(Info<ConcurrentMap<K, V>> oldInfo, Info<ConcurrentMap<K, V>> newInfo)
        {
            Map<K, V> oldMap = oldInfo.getObject();
            Map<K, V> newMap = new HashMap<K, V>(newInfo.getObject());
            for (Map.Entry<K, V> oldEntry : oldMap.entrySet())
            {
                K key = oldEntry.getKey();
                V newValue = newMap.remove(key);
                Entry<K, V> entry = new Entry<K, V>(key, oldEntry.getValue(), newValue);
                if (newValue == null)
                    oortMap.notifyEntryRemoved(newInfo, entry);
                else
                    oortMap.notifyEntryPut(newInfo, entry);
            }
            for (Map.Entry<K, V> newEntry : newMap.entrySet())
            {
                Entry<K, V> entry = new Entry<K, V>(newEntry.getKey(), null, newEntry.getValue());
                oortMap.notifyEntryPut(newInfo, entry);
            }
        }

        public void onRemoved(Info<ConcurrentMap<K, V>> info)
        {
            for (Map.Entry<K, V> oldEntry : info.getObject().entrySet())
            {
                Entry<K, V> entry = new Entry<K, V>(oldEntry.getKey(), oldEntry.getValue(), null);
                oortMap.notifyEntryRemoved(info, entry);
            }
        }
    }

    public static class Entry<K, V>
    {
        private final K key;
        private final V oldValue;
        private final V newValue;

        public Entry(K key, V oldValue, V newValue)
        {
            this.key = key;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        public K getKey()
        {
            return key;
        }

        public V getOldValue()
        {
            return oldValue;
        }

        public V getNewValue()
        {
            return newValue;
        }

        @Override
        public String toString()
        {
            return String.format("(%s=%s->%s)", getKey(), getOldValue(), getNewValue());
        }
    }
}
