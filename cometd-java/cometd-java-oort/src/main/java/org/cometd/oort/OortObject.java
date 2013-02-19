/*
 * Copyright (c) 2010 the original author or authors.
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
import java.util.Collection;
import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OortObject<T> implements Oort.CometListener, ServerChannel.MessageListener
{
    public static final String OORT_OBJECTS_CHANNEL = "/oort/objects";

    private final Map<String, MetaData<T>> objects = new ConcurrentHashMap<String, MetaData<T>>();
    private final List<Listener<T>> listeners = new CopyOnWriteArrayList<Listener<T>>();
    protected final Logger logger;
    private final Oort oort;
    private final String name;
    private final LocalSession sender;

    public OortObject(Oort oort, String name, T initial)
    {
        this.oort = oort;
        this.name = name;
        logger = LoggerFactory.getLogger(getClass().getName() + "." + oort.getURL() + "." + name);
        setLocal(initial);
        BayeuxServer bayeuxServer = oort.getBayeuxServer();
        this.sender = bayeuxServer.newLocalSession(getClass().getSimpleName() + "." + name);
        this.sender.handshake();
        oort.addCometListener(this);
        bayeuxServer.createIfAbsent(OORT_OBJECTS_CHANNEL, new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setPersistent(true);
            }
        });
        bayeuxServer.getChannel(OORT_OBJECTS_CHANNEL).addListener(this);
        oort.observeChannel(OORT_OBJECTS_CHANNEL);
    }

    public Oort getOort()
    {
        return oort;
    }

    public String getName()
    {
        return name;
    }

    public LocalSession getLocalSession()
    {
        return sender;
    }

    public void cometJoined(Event event)
    {
        logger.debug("Oort {} joined", event.getCometURL());
        publish();
    }

    public void cometLeft(Event event)
    {
        logger.debug("Oort {} left", event.getCometURL());
        MetaData<T> metaData = objects.remove(event.getCometURL());
        notifyOnRemoved(metaData);
    }

    public void addListener(Listener<T> listener)
    {
        listeners.add(listener);
    }

    public void removeListener(Listener<T> listener)
    {
        listeners.remove(listener);
    }

    protected void notifyOnUpdated(MetaData<T> oldMetaData, MetaData<T> newMetaData)
    {
        for (Listener<T> listener : listeners)
        {
            try
            {
                listener.onUpdated(oldMetaData, newMetaData);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    protected void notifyOnRemoved(MetaData<T> metaData)
    {
        for (Listener<T> listener : listeners)
        {
            try
            {
                listener.onRemoved(metaData);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
    {
        Object data = message.getData();
        if (data instanceof Map)
            return onMessage((Map<String, Object>)data);

        if (data instanceof Object[])
        {
            data = Arrays.asList((Object[])data);
        }
        if (data instanceof List)
        {
            boolean result = true;
            for (Object element : (List<?>)data)
            {
                if (element instanceof Map)
                    result &= onMessage((Map<String, Object>)data);
            }
            return result;
        }
        return true;
    }

    protected boolean onMessage(Map<String, Object> data)
    {
        boolean sameOort = oort.getURL().equals(data.get(MetaData.OORT_URL_FIELD));
        boolean sameName = getName().equals(data.get(MetaData.NAME_FIELD));
        if (!sameOort && sameName)
            onObject(data);
        return true;
    }

    protected void onObject(Map<String, Object> data)
    {
        logger.debug("Cloud shared object {}", data);
        // Default behavior is to replace
        MetaData<T> newMetaData = new MetaData<T>(data);
        MetaData<T> oldMetaData = objects.put(newMetaData.getOortURL(), newMetaData);
        notifyOnUpdated(oldMetaData, newMetaData);
    }

    public T getLocal()
    {
        return getRemote(oort.getURL());
    }

    public void setLocal(T local)
    {
        if (local == null)
            throw new NullPointerException();
        Map<String, Object> data = new HashMap<String, Object>();
        data.put(MetaData.OORT_URL_FIELD, oort.getURL());
        data.put(MetaData.NAME_FIELD, getName());
        data.put(MetaData.OBJECT_FIELD, local);
        MetaData<T> metaData = new MetaData<T>(data);
        objects.put(oort.getURL(), metaData);
    }

    public T getRemote(String oortURL)
    {
        MetaData<T> metaData = getMetaData(oortURL);
        return metaData == null ? null : metaData.getObject();
    }

    protected MetaData<T> getMetaData(String oortURL)
    {
        return objects.get(oortURL);
    }

    public T get(MergeStrategy<T> strategy)
    {
        return strategy.merge(objects.values());
    }

    public void publish()
    {
        MetaData<T> metaData = getMetaData(oort.getURL());
        if (metaData != null)
        {
            Map<String, Object> data = metaData.asMap();
            logger.debug("Cloud sharing object {}", metaData);
            BayeuxServer bayeuxServer = oort.getBayeuxServer();
            bayeuxServer.getChannel(OORT_OBJECTS_CHANNEL).publish(sender, data, null);
        }
    }

    public static class MetaData<E>
    {
        public static final String OORT_URL_FIELD = "oortURL";
        public static final String NAME_FIELD = "name";
        public static final String OBJECT_FIELD = "object";
        public static final String TYPE_FIELD = "type";
        public static final String ACTION_FIELD = "action";

        private final Map<String, Object> data;

        public MetaData(Map<String, Object> data)
        {
            this.data = data;
        }

        public String getOortURL()
        {
            return (String)data.get(OORT_URL_FIELD);
        }

        public String getName()
        {
            return (String)data.get(NAME_FIELD);
        }

        public E getObject()
        {
            return (E)data.get(OBJECT_FIELD);
        }

        public Map<String, Object> asMap()
        {
            Map<String, Object> result = new HashMap<String, Object>();
            result.put(OORT_URL_FIELD, getOortURL());
            result.put(NAME_FIELD, getName());
            result.put(OBJECT_FIELD, getObject());
            return result;
        }

        @Override
        public String toString()
        {
            return String.format("'%s' (from %s): %s", getName(), getOortURL(), getObject());
        }
    }

    public interface MergeStrategy<S>
    {
        public S merge(Collection<MetaData<S>> values);
    }

    public static class UnionMergeStrategyList<E> implements MergeStrategy<List<E>>
    {
        public List<E> merge(Collection<MetaData<List<E>>> values)
        {
            List<E> result = new ArrayList<E>();
            for (MetaData<List<E>> value : values)
                result.addAll(value.getObject());
            return result;
        }
    }

    public static class UnionMergeStrategyMap<K, V> implements MergeStrategy<Map<K, V>>
    {
        public Map<K, V> merge(Collection<MetaData<Map<K, V>>> values)
        {
            Map<K, V> result = new HashMap<K, V>();
            for (MetaData<Map<K, V>> value : values)
                result.putAll(value.getObject());
            return result;
        }
    }

    public interface Listener<T> extends EventListener
    {
        public void onUpdated(MetaData<T> oldMetaData, MetaData<T> newMetaData);

        public void onRemoved(MetaData<T> metaData);

        public static class Adapter<Q> implements Listener<Q>
        {
            public void onUpdated(MetaData<Q> oldMetaData, MetaData<Q> newMetaData)
            {
            }

            public void onRemoved(MetaData<Q> metaData)
            {
            }
        }
    }
}
