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
    private static final String OORT_OBJECTS_CHANNEL = "/oort/objects";

    private final Map<String, MetaData<T>> objects = new ConcurrentHashMap<String, MetaData<T>>();
    private final List<Listener<T>> listeners = new CopyOnWriteArrayList<Listener<T>>();
    private final Logger logger;
    private final Oort oort;
    private final String name;
    private final LocalSession sender;

    public OortObject(Oort oort, String name)
    {
        this(oort, name, null);
    }

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

    public String getName()
    {
        return name;
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

    private void notifyOnAdded(MetaData<T> metaData)
    {
        for (Listener<T> listener : listeners)
        {
            try
            {
                listener.onAdded(metaData);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyOnRemoved(MetaData<T> metaData)
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
        Map<String, Object> data = message.getDataAsMap();
        MetaData<T> metaData = MetaData.from(data);
        boolean sameOort = oort.getURL().equals(metaData.getOortURL());
        boolean sameName = getName().equals(metaData.getName());
        if (!sameOort && sameName)
        {
            logger.debug("Cloud shared object {}", metaData);
            // TODO: decide the local merge strategy via hints ?
            // TODO: for now just replace
            objects.put(metaData.getOortURL(), metaData);
            notifyOnAdded(metaData);
        }
        return true;
    }

    public T getLocal()
    {
        return getRemote(oort.getURL());
    }

    public void setLocal(T local)
    {
        if (local != null)
        {
            MetaData<T> metaData = new MetaData<T>(oort.getURL(), name, local);
            objects.put(oort.getURL(), metaData);
        }
    }

    public T getRemote(String oortURL)
    {
        MetaData<T> metaData = objects.get(oortURL);
        return metaData == null ? null : metaData.get();
    }

    public T get(MergeStrategy<T> strategy)
    {
        return strategy.merge(objects.values());
    }

    public void publish()
    {
        MetaData<T> metaData = objects.get(oort.getURL());
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
        private static final String OORT_URL_FIELD = "oortURL";
        private static final String NAME_FIELD = "name";
        private static final String OBJECT_FIELD = "object";

        private final String oortURL;
        private final String name;
        private final E object;

        public MetaData(String oortURL, String name, E object)
        {
            this.oortURL = oortURL;
            this.name = name;
            this.object = object;
        }

        public String getOortURL()
        {
            return oortURL;
        }

        public String getName()
        {
            return name;
        }

        public E get()
        {
            return object;
        }

        public Map<String, Object> asMap()
        {
            Map<String, Object> result = new HashMap<String, Object>();
            result.put(OORT_URL_FIELD, getOortURL());
            result.put(NAME_FIELD, getName());
            result.put(OBJECT_FIELD, get());
            return result;
        }

        public static <T> MetaData<T> from(Map<String, Object> data)
        {
            String oortURL = (String)data.get(OORT_URL_FIELD);
            String name = (String)data.get(NAME_FIELD);
            T object = (T)data.get(OBJECT_FIELD);
            return new MetaData<T>(oortURL, name, object);
        }

        @Override
        public String toString()
        {
            return String.format("'%s' (from %s): %s", getName(), getOortURL(), get());
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
                result.addAll(value.get());
            return result;
        }
    }

    public static class UnionMergeStrategyMap<K, V> implements MergeStrategy<Map<K, V>>
    {
        public Map<K, V> merge(Collection<MetaData<Map<K, V>>> values)
        {
            Map<K, V> result = new HashMap<K, V>();
            for (MetaData<Map<K, V>> value : values)
                result.putAll(value.get());
            return result;
        }
    }

    public interface Listener<T> extends EventListener
    {
        public void onAdded(MetaData<T> metaData);

        public void onRemoved(MetaData<T> metaData);

        public static class Adapter<Q> implements Listener<Q>
        {
            public void onAdded(MetaData<Q> metaData)
            {
            }

            public void onRemoved(MetaData<Q> metaData)
            {
            }
        }
    }
}
