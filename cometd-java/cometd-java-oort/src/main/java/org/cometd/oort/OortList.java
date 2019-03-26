/*
 * Copyright (c) 2008-2019 the original author or authors.
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;

/**
 * <p>A specialized oort object whose entity is a {@link List}.</p>
 * <p>{@link OortList} specializes {@code OortObject} and allows optimized replication of elements
 * across the cluster: instead of replicating the whole list, that may be contain a lot of elements,
 * only elements that are added or removed are replicated.</p>
 * <p>Applications can use {@link #addAndShare(Result, Object[])} and {@link #removeAndShare(Result, Object[])}
 * to broadcast changes related to elements, as well as {@link #setAndShare(Object, Result)} to
 * change the whole list.</p>
 * <p>When one or more elements are changed, {@link ElementListener}s are notified.
 * {@link DeltaListener} converts whole list updates triggered by {@link #setAndShare(Object, Result)}
 * into events for {@link ElementListener}s, giving applications a single listener type to implement
 * their business logic.</p>
 *
 * @param <E> the element type
 */
public class OortList<E> extends OortContainer<List<E>> {
    private static final String TYPE_FIELD_ELEMENT_VALUE = "oort.list.element";
    private static final String ACTION_FIELD_ADD_VALUE = "oort.list.add";
    private static final String ACTION_FIELD_REMOVE_VALUE = "oort.list.remove";

    private final List<ElementListener<E>> listeners = new CopyOnWriteArrayList<>();

    public OortList(Oort oort, String name, Factory<List<E>> factory) {
        super(oort, name, factory);
    }

    public void addElementListener(ElementListener<E> listener) {
        listeners.add(listener);
    }

    public void removeElementListener(ElementListener<E> listener) {
        listeners.remove(listener);
    }

    public void removeElementListeners() {
        listeners.clear();
    }

    /**
     * Returns whether the given {@code element} is present in the local entity list of this node.
     * Differently from {@link #isPresent(Object)}, only the local entity list is scanned.
     *
     * @param element the element to test for presence
     * @return true if the {@code element} is contained in the local entity list, false otherwise
     */
    public boolean contains(E element) {
        return getInfo(getOort().getURL()).getObject().contains(element);
    }

    /**
     * Returns whether the given {@code element} is present in one of the entity lists of all nodes.
     * Differently from {@link #contains(Object)} entity lists of all nodes are scanned.
     *
     * @param element the element to test for presence
     * @return true if the {@code element} is contained in one of the entity lists of all nodes, false otherwise
     */
    public boolean isPresent(E element) {
        for (Info<List<E>> info : this) {
            if (info.getObject().contains(element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <p>Adds the given {@code elements} to the local entity list,
     * and then broadcasts the addition to all nodes in the cluster.</p>
     * <p>Calling this method triggers notifications {@link ElementListener}s,
     * both on this node and on remote nodes.</p>
     * <p>The element is guaranteed to be added not when this method returns,
     * but when the {@link Result} parameter is notified.</p>
     *
     * @param callback the callback invoked with whether at least one of the elements was added to the local entity list
     *                 or {@code null} if there is no interest in knowing whether elements were added
     * @param elements the elements to add
     */
    public void addAndShare(Result<Boolean> callback, E... elements) {
        Data<Boolean> data = new Data<>(6, callback);
        data.put(Info.OORT_URL_FIELD, getOort().getURL());
        data.put(Info.NAME_FIELD, getName());
        data.put(Info.OBJECT_FIELD, elements);
        data.put(Info.TYPE_FIELD, TYPE_FIELD_ELEMENT_VALUE);
        data.put(Info.ACTION_FIELD, ACTION_FIELD_ADD_VALUE);

        if (logger.isDebugEnabled()) {
            logger.debug("Sharing list add {}", data);
        }
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(getChannelName()).publish(getLocalSession(), data, Promise.noop());
    }

    /**
     * <p>Removes the given {@code elements} to the local entity list,
     * and then broadcasts the removal to all nodes in the cluster.</p>
     * <p>Calling this method triggers notifications {@link ElementListener}s,
     * both on this node and on remote nodes.</p>
     * <p>The element is guaranteed to be removed not when this method returns,
     * but when the {@link Result} parameter is notified.</p>
     *
     * @param callback the callback invoked with whether at least one of the elements was removed to the local entity list
     *                 or {@code null} if there is no interest in knowing whether elements were removed
     * @param elements the elements to remove
     */
    public void removeAndShare(Result<Boolean> callback, E... elements) {
        Data<Boolean> data = new Data<>(6, callback);
        data.put(Info.OORT_URL_FIELD, getOort().getURL());
        data.put(Info.NAME_FIELD, getName());
        data.put(Info.OBJECT_FIELD, elements);
        data.put(Info.TYPE_FIELD, TYPE_FIELD_ELEMENT_VALUE);
        data.put(Info.ACTION_FIELD, ACTION_FIELD_REMOVE_VALUE);

        if (logger.isDebugEnabled()) {
            logger.debug("Sharing list remove {}", data);
        }
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(getChannelName()).publish(getLocalSession(), data, Promise.noop());
    }

    @Override
    protected boolean isItemUpdate(Map<String, Object> data) {
        return TYPE_FIELD_ELEMENT_VALUE.equals(data.get(Info.TYPE_FIELD));
    }

    @Override
    protected void onItem(Info<List<E>> info, Map<String, Object> data) {
        // Retrieve elements.
        Object object = data.get(Info.OBJECT_FIELD);
        if (object instanceof Object[]) {
            object = Arrays.asList((Object[])object);
        }
        @SuppressWarnings("unchecked")
        List<E> elements = (List<E>)object;

        // Perform the action.
        List<E> list = info.getObject();
        boolean result;
        String action = (String)data.get(Info.ACTION_FIELD);
        switch (action) {
            case ACTION_FIELD_ADD_VALUE:
                result = list.addAll(elements);
                break;
            case ACTION_FIELD_REMOVE_VALUE:
                result = list.removeAll(elements);
                break;
            default:
                throw new IllegalArgumentException(action);
        }

        // Update the version.
        info.put(Info.VERSION_FIELD, data.get(Info.VERSION_FIELD));

        // Notify.
        if (logger.isDebugEnabled()) {
            logger.debug("{} list {} of {}", info.isLocal() ? "Local" : "Remote", action, elements);
        }
        switch (action) {
            case ACTION_FIELD_ADD_VALUE:
                notifyElementsAdded(info, elements);
                break;
            case ACTION_FIELD_REMOVE_VALUE:
                notifyElementsRemoved(info, elements);
                break;
        }

        if (data instanceof Data) {
            ((Data<Boolean>)data).setResult(result);
        }
    }

    private void notifyElementsAdded(Info<List<E>> info, List<E> elements) {
        for (ElementListener<E> listener : listeners) {
            try {
                listener.onAdded(info, elements);
            } catch (Throwable x) {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    private void notifyElementsRemoved(Info<List<E>> info, List<E> elements) {
        for (ElementListener<E> listener : listeners) {
            try {
                listener.onRemoved(info, elements);
            } catch (Throwable x) {
                logger.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    /**
     * Listener for element events that update the entity list, either locally or remotely.
     *
     * @param <E> the element type
     */
    public interface ElementListener<E> extends EventListener {
        /**
         * Callback method invoked when elements are added to the entity list.
         *
         * @param info     the {@link Info} that was changed by the addition
         * @param elements the elements added
         */
        public default void onAdded(Info<List<E>> info, List<E> elements) {
        }

        /**
         * Callback method invoked when elements are removed from the entity list.
         *
         * @param info     the {@link Info} that was changed by the removal
         * @param elements the elements removed
         */
        public default void onRemoved(Info<List<E>> info, List<E> elements) {
        }

        /**
         * Empty implementation of {@link ElementListener}.
         *
         * @deprecated use {@link ElementListener} instead
         * @param <E> the element type
         */
        @Deprecated
        public static class Adapter<E> implements ElementListener<E> {
            @Override
            public void onAdded(Info<List<E>> info, List<E> elements) {
            }

            @Override
            public void onRemoved(Info<List<E>> info, List<E> elements) {
            }
        }
    }

    /**
     * <p>An implementation of {@link Listener} that converts whole list events into {@link ElementListener} events.</p>
     * <p>For example, if an entity list:</p>
     * <pre>
     * [A, B]
     * </pre>
     * <p>is replaced by a list:</p>
     * <pre>
     * [A, C, D]
     * </pre>
     * <p>then this listener generates two "add" events for {@code C} and {@code D}
     * and one "remove" event for {@code B}.</p>
     *
     * @param <E> the element type
     */
    public static class DeltaListener<E> implements Listener<List<E>> {
        private final OortList<E> oortList;

        public DeltaListener(OortList<E> oortList) {
            this.oortList = oortList;
        }

        @Override
        public void onUpdated(Info<List<E>> oldInfo, Info<List<E>> newInfo) {
            List<E> oldList = oldInfo == null ? Collections.emptyList() : oldInfo.getObject();
            List<E> newList = newInfo.getObject();

            List<E> added = new ArrayList<>(newList);
            added.removeAll(oldList);

            List<E> removed = new ArrayList<>(oldList);
            removed.removeAll(newList);

            if (!added.isEmpty()) {
                oortList.notifyElementsAdded(newInfo, added);
            }
            if (!removed.isEmpty()) {
                oortList.notifyElementsRemoved(newInfo, removed);
            }
        }

        @Override
        public void onRemoved(Info<List<E>> info) {
            oortList.notifyElementsRemoved(info, info.getObject());
        }
    }
}
