/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;

/**
 * <p>{@link JSONDataFilter} walks an object to see if it is
 * a JSON data structure and calls the appropriate methods
 * {@link #filterString(ServerSession, ServerChannel, String)},
 * {@link #filterNumber(ServerSession, ServerChannel, Number)},
 * {@link #filterBoolean(ServerSession, ServerChannel, Boolean)},
 * {@link #filterArray(ServerSession, ServerChannel, Object)},
 * {@link #filterCollection(ServerSession, ServerChannel, Collection)},
 * {@link #filterList(ServerSession, ServerChannel, List)},
 * {@link #filterMap(ServerSession, ServerChannel, Map)}.</p>
 * <p>Derived filters may override one or more of these methods to provide
 * filtering of specific types.</p>
 */
public class JSONDataFilter implements DataFilter {
    public void init(Object init) {
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object filter(ServerSession session, ServerChannel channel, Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof Map) {
            return filterMap(session, channel, (Map)data);
        }
        if (data instanceof List) {
            return filterList(session, channel, (List)data);
        }
        if (data instanceof Collection) {
            return filterCollection(session, channel, (Collection)data);
        }
        if (data.getClass().isArray()) {
            return filterArray(session, channel, data);
        }
        if (data instanceof Number) {
            return filterNumber(session, channel, (Number)data);
        }
        if (data instanceof Boolean) {
            return filterBoolean(session, channel, (Boolean)data);
        }
        if (data instanceof String) {
            return filterString(session, channel, (String)data);
        }
        return filterObject(session, channel, data);
    }

    protected Object filterString(ServerSession session, ServerChannel channel, String string) {
        return string;
    }

    protected Object filterBoolean(ServerSession session, ServerChannel channel, Boolean bool) {
        return bool;
    }

    protected Object filterNumber(ServerSession session, ServerChannel channel, Number number) {
        return number;
    }

    protected Object filterArray(ServerSession session, ServerChannel channel, Object array) {
        Object[] mutated = null;
        for (int i = 0; i < Array.getLength(array); ++i) {
            Object original = Array.get(array, i);
            Object filtered = filter(session, channel, original);
            if (original != filtered) {
                if (mutated == null) {
                    mutated = Arrays.copyOf((Object[])array, Array.getLength(array));
                }
                mutated[i] = filtered;
            }
        }
        return mutated == null ? array : mutated;
    }

    protected Object filterList(ServerSession session, ServerChannel channel, List<Object> list) {
        List<Object> mutated = null;
        for (int i = 0; i < list.size(); ++i) {
            Object original = list.get(i);
            Object filtered = filter(session, channel, original);
            if (original != filtered) {
                if (mutated == null) {
                    mutated = new ArrayList<>(list);
                }
                mutated.set(i, filtered);
            }
        }
        return mutated == null ? list : mutated;
    }

    protected Object filterCollection(ServerSession session, ServerChannel channel, Collection<Object> collection) {
        int index = 0;
        List<Object> mutated = null;
        for (Object original : collection) {
            Object filtered = filter(session, channel, original);
            if (original != filtered) {
                if (mutated == null) {
                    mutated = new ArrayList<>(collection.size());
                    int i = 0;
                    for (Object copy : collection) {
                        if (i == index) {
                            break;
                        }
                        mutated.add(copy);
                        ++i;
                    }
                }
                mutated.add(filtered);
            } else if (mutated != null) {
                mutated.add(original);
            }
            ++index;
        }
        return mutated == null ? collection : mutated;
    }

    protected Object filterMap(ServerSession session, ServerChannel channel, Map<String, Object> map) {
        Map<String, Object> mutated = null;
        for (Entry<String, Object> entry : map.entrySet()) {
            Object original = entry.getValue();
            Object filtered = filter(session, channel, original);
            if (original != filtered) {
                if (mutated == null) {
                    mutated = new HashMap<>(map);
                }
                mutated.put(entry.getKey(), filtered);
            }
        }
        return mutated == null ? map : mutated;
    }

    protected Object filterObject(ServerSession session, ServerChannel channel, Object data) {
        return data;
    }
}
