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

import java.util.Arrays;
import java.util.Collection;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OortObject<T> implements ConfigurableServerChannel.Initializer, Oort.CometListener, ServerChannel.MessageListener, Iterable<OortObject.Info<T>>
{
    public static final String OORT_OBJECTS_CHANNEL = "/oort/objects";

    private final ConcurrentMap<String, Info<T>> infos = new ConcurrentHashMap<String, Info<T>>();
    private final List<Listener<T>> listeners = new CopyOnWriteArrayList<Listener<T>>();
    protected final Logger logger;
    private final Oort oort;
    private final String name;
    private final Factory<T> factory;
    private final LocalSession sender;
    private final String channelName;

    public OortObject(Oort oort, String name, Factory<T> factory)
    {
        this.oort = oort;
        this.name = name;
        this.factory = factory;
        this.logger = LoggerFactory.getLogger(getClass().getName() + "." + oort.getURL() + "." + name);
        this.sender = oort.getBayeuxServer().newLocalSession(getClass().getSimpleName() + "." + name);
        this.channelName = OORT_OBJECTS_CHANNEL + "/" + name;
    }

    public void start()
    {
        setLocal(factory.newObject(null));
        sender.handshake();
        oort.addCometListener(this);
        oort.getBayeuxServer().createIfAbsent(channelName, this);
        oort.getBayeuxServer().getChannel(channelName).addListener(this);
        oort.observeChannel(channelName);
    }

    public void stop()
    {
        oort.deobserveChannel(channelName);
        oort.getBayeuxServer().getChannel(channelName).removeListener(this);
        oort.removeCometListener(this);
        sender.disconnect();
    }

    public void configureChannel(ConfigurableServerChannel channel)
    {
        channel.setPersistent(true);
    }

    public Oort getOort()
    {
        return oort;
    }

    public String getName()
    {
        return name;
    }

    public Factory<T> getFactory()
    {
        return factory;
    }

    public LocalSession getLocalSession()
    {
        return sender;
    }

    public String getChannelName()
    {
        return channelName;
    }

    public void cometJoined(Event event)
    {
        logger.debug("Oort {} joined", event.getCometURL());
        share();
    }

    public void cometLeft(Event event)
    {
        logger.debug("Oort {} left", event.getCometURL());
        Info<T> info = infos.remove(event.getCometURL());
        if (info != null)
        {
            logger.debug("Removed remote info {}", info);
            notifyRemoved(info);
        }
    }

    public Iterator<Info<T>> iterator()
    {
        return infos.values().iterator();
    }

    public Info<T> findInfo(T object)
    {
        for (Info<T> info : this)
        {
            if (info.getObject().equals(object))
                return info;
        }
        return null;
    }

    public void addListener(Listener<T> listener)
    {
        listeners.add(listener);
    }

    public void removeListener(Listener<T> listener)
    {
        listeners.remove(listener);
    }

    protected void notifyUpdated(Info<T> oldInfo, Info<T> newInfo)
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

    protected void notifyRemoved(Info<T> info)
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

    @SuppressWarnings("unchecked")
    public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message)
    {
        Object data = message.getData();
        if (data instanceof Map)
            return onMessage((Map<String, Object>)data);

        if (data instanceof Object[])
            data = Arrays.asList((Object[])data);

        if (data instanceof List)
        {
            boolean result = true;
            for (Object element : (List<?>)data)
            {
                if (element instanceof Map)
                    result &= onMessage((Map<String, Object>)element);
            }
            return result;
        }
        return true;
    }

    private boolean onMessage(Map<String, Object> data)
    {
        boolean sameName = getName().equals(data.get(Info.NAME_FIELD));
        if (sameName)
            onObject(data);
        return true;
    }

    protected void onObject(Map<String, Object> data)
    {
        logger.debug("Received data {}", data);

        // Convert the object, for example from a JSON serialized Map to a ConcurrentMap
        data.put(Info.OBJECT_FIELD, getFactory().newObject(data.get(Info.OBJECT_FIELD)));

        // Default behavior is to replace atomically
        Info<T> newInfo = new Info<T>(oort.getURL(), data);
        Info<T> oldInfo;
        String newOortURL = newInfo.getOortURL();
        boolean initial = Info.TYPE_FIELD_INITIAL_VALUE.equals(data.get(Info.TYPE_FIELD));
        if (initial)
        {
            // Make sure we don't overwrite existing data with initial data
            oldInfo = infos.putIfAbsent(newOortURL, newInfo);
            if (oldInfo == null)
            {
                logger.debug("Initialized info {}", newInfo);
                notifyUpdated(oldInfo, newInfo);
            }
        }
        else
        {
            if (newInfo.isLocal())
            {
                // It will always fail, but we will have a reference to the oldInfo
                oldInfo = infos.putIfAbsent(newOortURL, newInfo);
                // Notify a full difference
                notifyUpdated(null, oldInfo);
            }
            else
            {
                oldInfo = infos.put(newOortURL, newInfo);
                logger.debug("Replaced info {} with {}", oldInfo, newInfo);
                notifyUpdated(oldInfo, newInfo);
            }
        }

        // If we did not have an info for the new Oort, then it's a
        // new OortObject and we need to push our own data to it.
        if (oldInfo == null)
        {
            Map<String, Object> localData = new HashMap<String, Object>(getInfo(oort.getURL()));
            localData.put(Info.TYPE_FIELD, Info.TYPE_FIELD_INITIAL_VALUE);
            logger.debug("Pushing (to {}) local data {}", newOortURL, localData);
            OortComet oortComet = oort.getComet(newOortURL);
            if (oortComet != null)
                oortComet.getChannel(channelName).publish(localData);
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
        Info<T> info = new Info<T>(oort.getURL(), 3);
        info.put(Info.OORT_URL_FIELD, oort.getURL());
        info.put(Info.NAME_FIELD, getName());
        info.put(Info.OBJECT_FIELD, local);
        logger.debug("Setting local info {}", info);
        infos.put(oort.getURL(), info);
    }

    public T getRemote(String oortURL)
    {
        Info<T> info = getInfo(oortURL);
        return info == null ? null : info.getObject();
    }

    protected Info<T> getInfo(String oortURL)
    {
        return infos.get(oortURL);
    }

    public T merge(Merger<T> strategy)
    {
        return strategy.merge(infos.values());
    }

    public void share()
    {
        Info<T> info = getInfo(oort.getURL());
        if (info != null)
        {
            logger.debug("Sharing local info {}", info);
            BayeuxServer bayeuxServer = oort.getBayeuxServer();
            bayeuxServer.getChannel(channelName).publish(sender, info, null);
        }
    }

    public static class Info<E> extends HashMap<String, Object>
    {
        public static final String OORT_URL_FIELD = "oortURL";
        public static final String NAME_FIELD = "name";
        public static final String OBJECT_FIELD = "object";
        public static final String TYPE_FIELD = "type";
        public static final String TYPE_FIELD_INITIAL_VALUE = "initial";
        public static final String ACTION_FIELD = "action";

        // The local Oort URL
        private final String oortURL;

        protected Info(String oortURL, int capacity)
        {
            super(capacity);
            this.oortURL = oortURL;
        }

        protected Info(String oortURL, Map<? extends String, ?> map)
        {
            this(oortURL, 3);
            // Constructor used for storage, only keep the
            // required fields discarding metadata fields
            put(OORT_URL_FIELD, map.get(OORT_URL_FIELD));
            put(NAME_FIELD, map.get(NAME_FIELD));
            put(OBJECT_FIELD, map.get(OBJECT_FIELD));
        }

        public String getOortURL()
        {
            return (String)get(OORT_URL_FIELD);
        }

        public String getName()
        {
            return (String)get(NAME_FIELD);
        }

        @SuppressWarnings("unchecked")
        public E getObject()
        {
            return (E)get(OBJECT_FIELD);
        }

        public boolean isLocal()
        {
            return oortURL.equals(getOortURL());
        }

        @Override
        public String toString()
        {
            E object = getObject();
            String objectString = object instanceof Object[] ? Arrays.toString((Object[])object) : String.valueOf(object);
            return String.format("'%s' (from %s): %s", getName(), getOortURL(), objectString);
        }
    }

    public interface Factory<E>
    {
        public E newObject(Object representation);
    }

    public interface Merger<E>
    {
        public E merge(Collection<Info<E>> infos);
    }

    public interface Listener<T> extends EventListener
    {
        public void onUpdated(Info<T> oldInfo, Info<T> newInfo);

        public void onRemoved(Info<T> info);

        public static class Adapter<T> implements Listener<T>
        {
            public void onUpdated(Info<T> oldInfo, Info<T> newInfo)
            {
            }

            public void onRemoved(Info<T> info)
            {
            }
        }
    }
}
