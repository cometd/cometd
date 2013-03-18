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

    private final Map<String, Info<T>> objects = new ConcurrentHashMap<String, Info<T>>();
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
        // Nothing to do because it is too early to push local data:
        // the OortObject on the new Oort may not be setup yet
        logger.debug("Oort {} joined", event.getCometURL());
    }

    public void cometLeft(Event event)
    {
        logger.debug("Oort {} left", event.getCometURL());
        Info<T> info = objects.remove(event.getCometURL());
        logger.debug("Removed remote info {}", info);
        notifyOnRemoved(info);
    }

    public void addListener(Listener<T> listener)
    {
        listeners.add(listener);
    }

    public void removeListener(Listener<T> listener)
    {
        listeners.remove(listener);
    }

    protected void notifyOnUpdated(Info<T> oldInfo, Info<T> newInfo)
    {
        for (Listener<T> listener : listeners)
        {
            try
            {
                listener.onUpdated(oldInfo, newInfo);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    protected void notifyOnRemoved(Info<T> info)
    {
        for (Listener<T> listener : listeners)
        {
            try
            {
                listener.onRemoved(info);
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

    private boolean onMessage(Map<String, Object> data)
    {
        boolean sameOort = oort.getURL().equals(data.get(Info.OORT_URL_FIELD));
        boolean sameName = getName().equals(data.get(Info.NAME_FIELD));
        if (!sameOort && sameName)
            onObject(data);
        return true;
    }

    protected void onObject(Map<String, Object> data)
    {
        Info<T> newInfo = new Info<T>(data);
        logger.debug("Received remote info {}", newInfo);

        // Default behavior is to replace
        String newOortURL = newInfo.getOortURL();
        Info<T> oldInfo = objects.put(newOortURL, newInfo);

        notifyOnUpdated(oldInfo, newInfo);

        // If we did not have an info for the new Oort, then it's a
        // new OortObject and we need to push our own data to it.
        if (oldInfo == null)
        {
            Info<T> localInfo = getInfo(oort.getURL());
            logger.debug("Pushing (to {}) local info {}", newOortURL, localInfo);
            oort.getComet(newOortURL).getChannel(OORT_OBJECTS_CHANNEL).publish(localInfo);
        }
    }

    public T getLocal()
    {
        return getRemote(oort.getURL());
    }

    public void setLocal(T local)
    {
        if (local == null)
            throw new NullPointerException();
        Info<T> info = new Info<T>();
        info.put(Info.OORT_URL_FIELD, oort.getURL());
        info.put(Info.NAME_FIELD, getName());
        info.put(Info.OBJECT_FIELD, local);
        logger.debug("Setting local info {}", info);
        objects.put(oort.getURL(), info);
    }

    public T getRemote(String oortURL)
    {
        Info<T> info = getInfo(oortURL);
        return info == null ? null : info.getObject();
    }

    protected Info<T> getInfo(String oortURL)
    {
        return objects.get(oortURL);
    }

    public T get(MergeStrategy<T> strategy)
    {
        return strategy.merge(objects.values());
    }

    public void publish()
    {
        Info<T> info = getInfo(oort.getURL());
        if (info != null)
        {
            logger.debug("Sharing local info {}", info);
            BayeuxServer bayeuxServer = oort.getBayeuxServer();
            bayeuxServer.getChannel(OORT_OBJECTS_CHANNEL).publish(sender, info, null);
        }
    }

    public static class Info<E> extends HashMap<String, Object>
    {
        public static final String OORT_URL_FIELD = "oortURL";
        public static final String NAME_FIELD = "name";
        public static final String OBJECT_FIELD = "object";
        public static final String TYPE_FIELD = "type";
        public static final String ACTION_FIELD = "action";

        public Info()
        {
        }

        public Info(Map<? extends String, ?> map)
        {
            super(map);
        }

        public String getOortURL()
        {
            return (String)get(OORT_URL_FIELD);
        }

        public String getName()
        {
            return (String)get(NAME_FIELD);
        }

        public E getObject()
        {
            return (E)get(OBJECT_FIELD);
        }

        @Override
        public String toString()
        {
            return String.format("'%s' (from %s): %s", getName(), getOortURL(), getObject());
        }
    }

    public interface MergeStrategy<E>
    {
        public E merge(Collection<Info<E>> values);
    }

    public static class UnionMergeStrategyList<E> implements MergeStrategy<List<E>>
    {
        public List<E> merge(Collection<Info<List<E>>> values)
        {
            List<E> result = new ArrayList<E>();
            for (Info<List<E>> value : values)
                result.addAll(value.getObject());
            return result;
        }
    }

    public static class UnionMergeStrategyMap<K, V> implements MergeStrategy<Map<K, V>>
    {
        public Map<K, V> merge(Collection<Info<Map<K, V>>> values)
        {
            Map<K, V> result = new HashMap<K, V>();
            for (Info<Map<K, V>> value : values)
                result.putAll(value.getObject());
            return result;
        }
    }

    public interface Listener<T> extends EventListener
    {
        public void onUpdated(Info<T> oldInfo, Info<T> newInfo);

        public void onRemoved(Info<T> info);

        public static class Adapter<Q> implements Listener<Q>
        {
            public void onUpdated(Info<Q> oldInfo, Info<Q> newInfo)
            {
            }

            public void onRemoved(Info<Q> info)
            {
            }
        }
    }
}
