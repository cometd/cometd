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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.server.BayeuxServer;

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

    public List<EntryListener<K, V>> getEntryListeners()
    {
        return listeners;
    }

    public V putAndShare(K key, V value)
    {
        V result = getLocal().put(key, value);
        sharePut(key, value);
        return result;
    }

    public void sharePut(K key, V value)
    {
        ConcurrentMap<K, V> map = getLocal();
        if (!map.containsKey(key))
            throw new IllegalArgumentException("Key " + key + " is not present in " + map);

        Map<String, Object> entry = newEntry(key, value);
        Info<List<Map.Entry<K, V>>> info = new Info<List<Map.Entry<K, V>>>();
        info.put(Info.OORT_URL_FIELD, getOort().getURL());
        info.put(Info.NAME_FIELD, getName());
        info.put(Info.OBJECT_FIELD, Collections.singletonList(entry));
        info.put(Info.TYPE_FIELD, TYPE_FIELD_ENTRY_VALUE);
        info.put(Info.ACTION_FIELD, ACTION_FIELD_PUT_VALUE);

        logger.debug("Sharing put map entry info {}", info);
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(OORT_OBJECTS_CHANNEL).publish(getLocalSession(), info, null);
    }

    public V removeAndShare(K key)
    {
        V result = getLocal().remove(key);
        shareRemove(key);
        return result;
    }

    public void shareRemove(K key)
    {
        Map<String, Object> entry = newEntry(key, null);
        Info<List<Map.Entry<K, V>>> info = new Info<List<Map.Entry<K, V>>>();
        info.put(Info.OORT_URL_FIELD, getOort().getURL());
        info.put(Info.NAME_FIELD, getName());
        info.put(Info.OBJECT_FIELD, Collections.singletonList(entry));
        info.put(Info.TYPE_FIELD, TYPE_FIELD_ENTRY_VALUE);
        info.put(Info.ACTION_FIELD, ACTION_FIELD_REMOVE_VALUE);

        logger.debug("Sharing remove map entry info {}", info);
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(OORT_OBJECTS_CHANNEL).publish(getLocalSession(), info, null);
    }

    private Map<String, Object> newEntry(K key, V value)
    {
        Map<String, Object> result = new HashMap<String, Object>(2);
        result.put(KEY_FIELD, key);
        result.put(VALUE_FIELD, value);
        return result;
    }

    @Override
    protected void onObject(Map<String, Object> data)
    {
        if (TYPE_FIELD_ENTRY_VALUE.equals(data.get(Info.TYPE_FIELD)))
        {
            String remoteOortURL = (String)data.get(Info.OORT_URL_FIELD);
            Info<ConcurrentMap<K, V>> info = getInfo(remoteOortURL);
            if (info != null)
            {
                ConcurrentMap<K, V> map = info.getObject();

                // Handle entries
                Object object = data.get(Info.OBJECT_FIELD);
                if (object instanceof Object[])
                    object = Arrays.asList((Object[])object);
                List<Map.Entry<K, V>> entries = new ArrayList<Map.Entry<K, V>>();
                for (Map<String, Object> entry : (List<Map<String, Object>>)object)
                    entries.add(new Entry<K, V>((K)entry.get(KEY_FIELD), (V)entry.get(VALUE_FIELD)));

                String action = (String)data.get(Info.ACTION_FIELD);
                if (ACTION_FIELD_PUT_VALUE.equals(action))
                {
                    for (Map.Entry<K, V> entry : entries)
                        map.put(entry.getKey(), entry.getValue());
                    notifyEntryPut(info, entries);
                }
                else if (ACTION_FIELD_REMOVE_VALUE.equals(action))
                {
                    for (Map.Entry<K, V> entry : entries)
                        map.remove(entry.getKey());
                    notifyElementsRemoved(info, entries);
                }
            }
            else
            {
                logger.debug("Could not find info for {}", remoteOortURL);
            }
        }
        else
        {
            Object object = data.get(Info.OBJECT_FIELD);
            if (!(object instanceof ConcurrentMap))
            {
                ConcurrentMap<K, V> map = getFactory().newObject(object);
                data.put(Info.OBJECT_FIELD, map);
            }
            super.onObject(data);
        }
    }

    private void notifyEntryPut(Info<ConcurrentMap<K, V>> info, List<Map.Entry<K, V>> elements)
    {
        for (EntryListener<K, V> listener : getEntryListeners())
        {
            try
            {
                listener.onPut(info, elements);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyElementsRemoved(Info<ConcurrentMap<K, V>> info, List<Map.Entry<K, V>> elements)
    {
        for (EntryListener<K, V> listener : getEntryListeners())
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
        public void onPut(Info<ConcurrentMap<K, V>> info, List<Map.Entry<K, V>> elements);

        public void onRemoved(Info<ConcurrentMap<K, V>> info, List<Map.Entry<K, V>> elements);

        public static class Adapter<K, V> implements EntryListener<K, V>
        {
            public void onPut(Info<ConcurrentMap<K, V>> info, List<Map.Entry<K, V>> elements)
            {
            }

            public void onRemoved(Info<ConcurrentMap<K, V>> info, List<Map.Entry<K, V>> elements)
            {
            }
        }
    }

    private static class Entry<K, V> implements Map.Entry<K, V>
    {
        private final K key;
        private final V value;

        public Entry(K key, V value)
        {
            this.key = key;
            this.value = value;
        }

        public K getKey()
        {
            return key;
        }

        public V getValue()
        {
            return value;
        }

        public V setValue(V value)
        {
            throw new UnsupportedOperationException();
        }
    }
}
