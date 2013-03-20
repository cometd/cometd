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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class OortObjectFactories
{
    private OortObjectFactories()
    {
    }

    public static OortObject.Factory<Long> forLong()
    {
        return new LongFactory();
    }

    public static <K, V> OortObject.Factory<Map<K, V>> forMap()
    {
        return new MapFactory<K, V>();
    }

    public static <K, V> OortObject.Factory<ConcurrentMap<K, V>> forConcurrentMap()
    {
        return new ConcurrentMapFactory<K, V>();
    }

    public static <E> OortObject.Factory<List<E>> forConcurrentList()
    {
        return new ConcurrentListFactory<E>();
    }

    private static class LongFactory implements OortObject.Factory<Long>
    {
        public Long newObject(Object representation)
        {
            if (representation == null)
                return 0L;
            if (representation instanceof Number)
                return ((Number)representation).longValue();
            throw new IllegalArgumentException();
        }
    }

    private static class MapFactory<K, V> implements OortObject.Factory<Map<K, V>>
    {
        @SuppressWarnings("unchecked")
        public Map<K, V> newObject(Object representation)
        {
            if (representation == null)
                return new HashMap<K, V>();
            if (representation instanceof Map)
                return (Map<K, V>)representation;
            throw new IllegalArgumentException();
        }
    }

    private static class ConcurrentMapFactory<K, V> implements OortObject.Factory<ConcurrentMap<K, V>>
    {
        @SuppressWarnings("unchecked")
        public ConcurrentMap<K, V> newObject(Object representation)
        {
            if (representation == null)
                return new ConcurrentHashMap<K, V>();
            if (representation instanceof ConcurrentMap)
                return (ConcurrentMap<K, V>)representation;
            if (representation instanceof Map)
                return new ConcurrentHashMap<K, V>((Map<K, V>)representation);
            throw new IllegalArgumentException();
        }
    }

    private static class ConcurrentListFactory<E> implements OortObject.Factory<List<E>>
    {
        @SuppressWarnings("unchecked")
        public List<E> newObject(Object representation)
        {
            if (representation == null)
                return new CopyOnWriteArrayList<E>();
            if (representation instanceof CopyOnWriteArrayList)
                return (List<E>)representation;
            if (representation instanceof List)
                return new CopyOnWriteArrayList<E>((List<E>)representation);
            if (representation instanceof Object[])
            {
                List<E> result = new CopyOnWriteArrayList<E>();
                for (Object element : (Object[])representation)
                    result.add((E)element);
                return result;
            }
            throw new IllegalArgumentException();
        }
    }
}
