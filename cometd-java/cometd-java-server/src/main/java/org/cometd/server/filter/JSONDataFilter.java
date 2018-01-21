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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

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
            return filterList(from, to, (List)data);
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

        Object[] mutated = null;        
        for (int i=Array.getLength(array); i-->0;) {
            Object original = Array.get(array,i);
            Object filtered = filter(from, to , original);
            if (original!=filtered) {
                if (mutated==null)
                    mutated=Arrays.copyOf((Object[])array,Array.getLength(array));
                mutated[i] = filtered;
            }
        }

        return mutated==null?array:mutated;
    }

    protected Object filterList(ServerSession from, ServerChannel to, List<Object> list) {
        if (list == null) {
            return null;
        }

        List<Object> mutated = null;
        for (int i=list.size(); i-->0;) {
            Object original = list.get(i);
            Object filtered = filter(from, to ,original);
            if (original!=filtered) {
                if (mutated==null)
                    mutated = new ArrayList<>(list);
                mutated.set(i,filtered);
            }
        }
            
        return mutated==null?list:mutated;
    }
    
    protected Object filterCollection(ServerSession from, ServerChannel to, Collection<Object> collection) {
        if (collection == null) {
            return null;
        }

        List<Object> mutated = null;
        for (Iterator<Object> i = collection.iterator(); i.hasNext();) {
            Object original = i.next();
            Object filtered = filter(from, to ,original);
            if (original!=filtered) {
                if (mutated==null) {
                    mutated = new ArrayList<>(collection.size());
                    for (Object copy : collection) {
                        if (copy==original)
                            break;
                        mutated.add(copy);
                    }
                }
                
                mutated.add(filtered);
            }
            else if (mutated!=null) {
                mutated.add(original);
            }
        }
         
        return mutated==null?collection:mutated;
    }

    
        
    protected Object filterMap(ServerSession from, ServerChannel to, Map<Object, Object> map) {
        if (map == null) {
            return null;
        }

        Map<Object, Object> mutated = null;
        for (Entry<Object, Object> entry : map.entrySet()) {
            Object original = entry.getValue();
            Object filtered = filter(from, to ,original);
            if (original!=filtered) {
                if (mutated==null)
                    mutated = new HashMap<>(map);
                mutated.put(entry.getKey(),filtered);
            }
        }
            
        return mutated==null?map:mutated;
    }

    protected Object filterObject(ServerSession from, ServerChannel to, Object obj) {
        return obj;
    }
}
