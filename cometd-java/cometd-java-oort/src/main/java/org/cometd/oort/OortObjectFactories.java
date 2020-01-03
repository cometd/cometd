/*
 * Copyright (c) 2008-2020 the original author or authors.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class OortObjectFactories {
    private OortObjectFactories() {
    }

    public static OortObject.Factory<Boolean> forBoolean(boolean defaultValue) {
        return new BooleanFactory(defaultValue);
    }

    public static OortObject.Factory<Long> forLong(long defaultValue) {
        return new LongFactory(defaultValue);
    }

    public static OortObject.Factory<String> forString(String defaultValue) {
        return new StringFactory(defaultValue);
    }

    public static <K, V> OortObject.Factory<Map<K, V>> forMap() {
        return forMap(new HashMap<K, V>());
    }

    public static <K, V> OortObject.Factory<Map<K, V>> forMap(Map<K, V> defaultValue) {
        return new MapFactory<>(defaultValue);
    }

    public static <K, V> OortObject.Factory<ConcurrentMap<K, V>> forConcurrentMap() {
        return forConcurrentMap(new HashMap<K, V>());
    }

    public static <K, V> OortObject.Factory<ConcurrentMap<K, V>> forConcurrentMap(Map<K, V> defaultValue) {
        return new ConcurrentMapFactory<>(defaultValue);
    }

    public static <E> OortObject.Factory<List<E>> forConcurrentList() {
        return forConcurrentList(new ArrayList<E>());
    }

    public static <E> OortObject.Factory<List<E>> forConcurrentList(List<E> defaultValue) {
        return new ConcurrentListFactory<>(defaultValue);
    }

    private static class BooleanFactory implements OortObject.Factory<Boolean> {
        private final boolean defaultValue;

        public BooleanFactory(boolean defaultValue) {
            this.defaultValue = defaultValue;
        }

        @Override
        public Boolean newObject(Object representation) {
            if (representation == null) {
                return defaultValue;
            }
            if (representation instanceof Boolean) {
                return (Boolean)representation;
            }
            return Boolean.valueOf(representation.toString());
        }
    }

    private static class LongFactory implements OortObject.Factory<Long> {
        private final long defaultValue;

        public LongFactory(long defaultValue) {
            this.defaultValue = defaultValue;
        }

        @Override
        public Long newObject(Object representation) {
            if (representation == null) {
                return defaultValue;
            }
            if (representation instanceof Number) {
                return ((Number)representation).longValue();
            }
            throw new IllegalArgumentException();
        }
    }

    private static class StringFactory implements OortObject.Factory<String> {
        private final String defaultValue;

        public StringFactory(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        @Override
        public String newObject(Object representation) {
            if (representation == null) {
                return defaultValue;
            }
            if (representation instanceof String) {
                return (String)representation;
            }
            return String.valueOf(representation);
        }
    }

    private static class MapFactory<K, V> implements OortObject.Factory<Map<K, V>> {
        private final Map<K, V> defaultValue;

        public MapFactory(Map<K, V> defaultValue) {
            this.defaultValue = defaultValue;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<K, V> newObject(Object representation) {
            if (representation == null) {
                return new HashMap<>(defaultValue);
            }
            if (representation instanceof Map) {
                return (Map<K, V>)representation;
            }
            throw new IllegalArgumentException();
        }
    }

    private static class ConcurrentMapFactory<K, V> implements OortObject.Factory<ConcurrentMap<K, V>> {
        private final Map<K, V> defaultValue;

        public ConcurrentMapFactory(Map<K, V> defaultValue) {
            this.defaultValue = defaultValue;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ConcurrentMap<K, V> newObject(Object representation) {
            if (representation == null) {
                return new ConcurrentHashMap<>(defaultValue);
            }
            if (representation instanceof ConcurrentMap) {
                return (ConcurrentMap<K, V>)representation;
            }
            if (representation instanceof Map) {
                return new ConcurrentHashMap<>((Map<K, V>)representation);
            }
            throw new IllegalArgumentException();
        }
    }

    private static class ConcurrentListFactory<E> implements OortObject.Factory<List<E>> {
        private final List<E> defaultValue;

        public ConcurrentListFactory(List<E> defaultValue) {
            this.defaultValue = defaultValue;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<E> newObject(Object representation) {
            if (representation == null) {
                return new CopyOnWriteArrayList<>(defaultValue);
            }
            if (representation instanceof CopyOnWriteArrayList) {
                return (List<E>)representation;
            }
            if (representation instanceof List) {
                return new CopyOnWriteArrayList<>((List<E>)representation);
            }
            if (representation instanceof Object[]) {
                List<E> result = new CopyOnWriteArrayList<>();
                for (Object element : (Object[])representation) {
                    result.add((E)element);
                }
                return result;
            }
            throw new IllegalArgumentException();
        }
    }
}
