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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class OortObjectMergers
{
    private OortObjectMergers()
    {
    }

    public static OortObject.Merger<Long> longSum()
    {
        return new LongSumMerger();
    }

    public static OortObject.Merger<AtomicLong> concurrentLongSum()
    {
        return new AtomicLongSumMerger();
    }

    public static <K, V> OortObject.Merger<Map<K, V>> mapUnion()
    {
        return new MapUnionMerger<K, V>();
    }

    public static <K, V> OortObject.Merger<ConcurrentMap<K, V>> concurrentMapUnion()
    {
        return new ConcurrentMapUnionMerger<K, V>();
    }

    public static <E> OortObject.Merger<List<E>> listUnion()
    {
        return new ListUnionMerger<E>();
    }

    private static class LongSumMerger implements OortObject.Merger<Long>
    {
        public Long merge(Collection<OortObject.Info<Long>> infos)
        {
            long sum = 0;
            for (OortObject.Info<Long> info : infos)
                sum += info.getObject();
            return sum;
        }
    }

    private static class AtomicLongSumMerger implements OortObject.Merger<AtomicLong>
    {
        public AtomicLong merge(Collection<OortObject.Info<AtomicLong>> infos)
        {
            AtomicLong sum = new AtomicLong();
            for (OortObject.Info<AtomicLong> info : infos)
                sum.addAndGet(info.getObject().get());
            return sum;
        }
    }

    private static class MapUnionMerger<K, V> implements OortObject.Merger<Map<K, V>>
    {
        public Map<K, V> merge(Collection<OortObject.Info<Map<K, V>>> infos)
        {
            Map<K, V> result = new HashMap<K, V>();
            for (OortObject.Info<Map<K, V>> value : infos)
                result.putAll(value.getObject());
            return result;
        }
    }

    private static class ConcurrentMapUnionMerger<K, V> implements OortObject.Merger<ConcurrentMap<K, V>>
    {
        public ConcurrentMap<K, V> merge(Collection<OortObject.Info<ConcurrentMap<K, V>>> infos)
        {
            ConcurrentMap<K, V> result = new ConcurrentHashMap<K, V>();
            for (OortObject.Info<ConcurrentMap<K, V>> value : infos)
                result.putAll(value.getObject());
            return result;
        }
    }

    public static class ListUnionMerger<E> implements OortObject.Merger<List<E>>
    {
        public List<E> merge(Collection<OortObject.Info<List<E>>> infos)
        {
            List<E> result = new ArrayList<E>();
            for (OortObject.Info<List<E>> value : infos)
                result.addAll(value.getObject());
            return result;
        }
    }
}
