/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.server.filter;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;

/**
 * <p>{@link JSONDataFilter} walks an object to see if it is a JSON data structure
 * and calls the appropriate protected method {@link #filterString(String)},
 * {@link #filterNumber(Number)}, {@link #filterBoolean(Boolean)},
 * {@link #filterArray(ServerSession, ServerChannel, Object)} or
 * {@link #filterMap(ServerSession, ServerChannel, Map)}.</p>
 * <p>Derived filters may override one or more of these methods to provide
 * filtering of specific types.</p>
 */
public class JSONDataFilter implements DataFilter {
    public void init(Object init) {
    }

    @Override
    public Object filter(ServerSession from, ServerChannel to, Object data) {
        if (data == null) {
            return null;
        }

        if (data instanceof Map) {
            return filterMap(from, to, (Map)data);
        }
        if (data instanceof List) {
            return filterArray(from, to, ((List)data).toArray());
        }
        if (data instanceof Collection) {
            return filterArray(from, to, ((Collection)data).toArray());
        }
        if (data.getClass().isArray()) {
            return filterArray(from, to, data);
        }
        if (data instanceof Number) {
            return filterNumber((Number)data);
        }
        if (data instanceof Boolean) {
            return filterBoolean((Boolean)data);
        }
        if (data instanceof String) {
            return filterString((String)data);
        }
        return filterObject(from, to, data);
    }

    protected Object filterString(String string) {
        return string;
    }

    protected Object filterBoolean(Boolean bool) {
        return bool;
    }

    protected Object filterNumber(Number number) {
        return number;
    }

    protected Object filterArray(ServerSession from, ServerChannel to, Object array) {
        if (array == null) {
            return null;
        }

        int length = Array.getLength(array);

        for (int i = 0; i < length; i++) {
            Array.set(array, i, filter(from, to, Array.get(array, i)));
        }

        return array;
    }

    protected Object filterMap(ServerSession from, ServerChannel to, Map<String, Object> map) {
        if (map == null) {
            return null;
        }

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            entry.setValue(filter(from, to, entry.getValue()));
        }

        return map;
    }

    protected Object filterObject(ServerSession from, ServerChannel to, Object obj) {
        return obj;
    }
}
