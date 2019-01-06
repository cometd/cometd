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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * The equivalent of an {@code OortMap&lt;Long,V&gt;}.
 *
 * @param <V> the value type
 */
public class OortLongMap<V> extends OortMap<Long, V> {
    public OortLongMap(Oort oort, String name, Factory<ConcurrentMap<Long, V>> factory) {
        super(oort, name, factory);
    }

    @Override
    protected Object deserialize(Object object) {
        // Convert keys from String (as they were created by JSON libraries)
        // to Long as required by the key type of this class.
        @SuppressWarnings("unchecked")
        Map<String, V> map = (Map<String, V>)object;
        if (map.isEmpty()) {
            return object;
        }
        Map<Long, V> result = new HashMap<>(map.size());
        for (Map.Entry<String, V> entry : map.entrySet()) {
            result.put(Long.parseLong(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
