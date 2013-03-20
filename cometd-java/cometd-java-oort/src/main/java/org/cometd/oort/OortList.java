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
import java.util.Collections;
import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.server.BayeuxServer;

public class OortList<E> extends OortObject<List<E>>
{
    public static final String TYPE_FIELD_ELEMENT_VALUE = "oort.list.element";
    public static final String ACTION_FIELD_ADD_VALUE = "oort.list.add";
    public static final String ACTION_FIELD_REMOVE_VALUE = "oort.list.remove";

    private final List<ElementListener<E>> listeners = new CopyOnWriteArrayList<ElementListener<E>>();

    public OortList(Oort oort, String name, Factory<List<E>> factory)
    {
        super(oort, name, factory);
    }

    public void addElementListener(ElementListener<E> listener)
    {
        listeners.add(listener);
    }

    public void removeElementListener(ElementListener<E> listener)
    {
        listeners.remove(listener);
    }

    public List<ElementListener<E>> getElementListeners()
    {
        return listeners;
    }

    public boolean addAndShare(E... elements)
    {
        boolean result = Collections.addAll(getLocal(), elements);
        if (result)
            shareAdd(elements);
        return result;
    }

    public void shareAdd(E... elements)
    {
        List<E> list = getLocal();
        for (E element : elements)
            if (!list.contains(element))
                throw new IllegalArgumentException("Element " + element + " is not an element of " + list);

        Info<List<E>> info = new Info<List<E>>();
        info.put(Info.OORT_URL_FIELD, getOort().getURL());
        info.put(Info.NAME_FIELD, getName());
        info.put(Info.OBJECT_FIELD, elements);
        info.put(Info.TYPE_FIELD, TYPE_FIELD_ELEMENT_VALUE);
        info.put(Info.ACTION_FIELD, ACTION_FIELD_ADD_VALUE);

        logger.debug("Sharing add list elements info {}", info);
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(OORT_OBJECTS_CHANNEL).publish(getLocalSession(), info, null);
    }

    public boolean removeAndShare(E... elements)
    {
        boolean result = false;
        List<E> list = getLocal();
        for (E element : elements)
            result |= list.remove(element);
        if (result)
            shareRemove(elements);
        return result;
    }

    public void shareRemove(E... elements)
    {
        Info<List<E>> info = new Info<List<E>>();
        info.put(Info.OORT_URL_FIELD, getOort().getURL());
        info.put(Info.NAME_FIELD, getName());
        info.put(Info.OBJECT_FIELD, elements);
        info.put(Info.TYPE_FIELD, TYPE_FIELD_ELEMENT_VALUE);
        info.put(Info.ACTION_FIELD, ACTION_FIELD_REMOVE_VALUE);

        logger.debug("Sharing remove list elements info {}", info);
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(OORT_OBJECTS_CHANNEL).publish(getLocalSession(), info, null);
    }

    @Override
    protected void onObject(Map<String, Object> data)
    {
        if (TYPE_FIELD_ELEMENT_VALUE.equals(data.get(Info.TYPE_FIELD)))
        {
            String remoteOortURL = (String)data.get(Info.OORT_URL_FIELD);
            Info<List<E>> info = getInfo(remoteOortURL);
            if (info != null)
            {
                List<E> list = info.getObject();

                // Handle element
                Object object = data.get(Info.OBJECT_FIELD);
                if (object instanceof Object[])
                    object = Arrays.asList((Object[])object);
                List<E> elements = (List<E>)object;

                String action = (String)data.get(Info.ACTION_FIELD);
                if (ACTION_FIELD_ADD_VALUE.equals(action))
                {
                    list.addAll(elements);
                    notifyElementsAdded(info, elements);
                }
                else if (ACTION_FIELD_REMOVE_VALUE.equals(action))
                {
                    list.removeAll(elements);
                    notifyElementsRemoved(info, elements);
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
            if (!(object instanceof List))
            {
                List<E> list = getFactory().newObject(object);
                data.put(Info.OBJECT_FIELD, list);
            }
            super.onObject(data);
        }
    }

    private void notifyElementsAdded(Info<List<E>> info, List<E> elements)
    {
        for (ElementListener<E> listener : getElementListeners())
        {
            try
            {
                listener.onAdded(info, elements);
            }
            catch (Exception x)
            {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyElementsRemoved(Info<List<E>> info, List<E> elements)
    {
        for (ElementListener<E> listener : getElementListeners())
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

    public interface ElementListener<E> extends EventListener
    {
        public void onAdded(Info<List<E>> info, List<E> elements);

        public void onRemoved(Info<List<E>> info, List<E> elements);

        public static class Adapter<E> implements ElementListener<E>
        {
            public void onAdded(Info<List<E>> info, List<E> elements)
            {
            }

            public void onRemoved(Info<List<E>> info, List<E> elements)
            {
            }
        }
    }
}
