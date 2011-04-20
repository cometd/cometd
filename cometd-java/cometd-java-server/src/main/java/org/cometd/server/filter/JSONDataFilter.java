// ========================================================================
// Copyright 2007 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.cometd.server.filter;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * JSON DataFilter This {@link DataFilter} walks an Object as if it was a call
 * to {@link JSON#toString(Object)} and calls the protected methods
 * {@link #filterString(String)}, {@link #filterNumber(Number)},
 * {@link #filterBoolean(Boolean)}, {@link #filterArray(Object, ClientImpl)} or
 * {@link #filterMap(Map, ClientImpl)} appropriate. Derived filters may override
 * one or more of these methods to provide filtering of specific types.
 *
 * @author gregw
 *
 */
public class JSONDataFilter implements DataFilter
{
    public void init(Object init)
    {
    }

    public Object filter(ServerSession from, ServerChannel to, Object data)
    {
        if (data == null)
            return null;

        if (data instanceof Map)
            return filterMap(from,to,(Map)data);
        if (data instanceof List)
            return filterArray(from,to,((List)data).toArray());
        if (data instanceof Collection)
            return filterArray(from,to,((Collection)data).toArray());
        if (data.getClass().isArray())
            return filterArray(from,to,data);
        if (data instanceof Number)
            return filterNumber((Number)data);
        if (data instanceof Boolean)
            return filterBoolean((Boolean)data);
        if (data instanceof String)
            return filterString((String)data);
        if (data instanceof JSON.Literal)
            return filterJSON(from,to,(JSON.Literal)data);
        if (data instanceof JSON.Generator)
            return filterJSON(from,to,(JSON.Generator)data);
        return filterObject(from,to,data);
    }

    protected Object filterString(String string)
    {
        return string;
    }

    protected Object filterBoolean(Boolean bool)
    {
        return bool;
    }

    protected Object filterNumber(Number number)
    {
        return number;
    }

    protected Object filterArray(ServerSession from, ServerChannel to, Object array)
    {
        if (array == null)
            return null;

        int length=Array.getLength(array);

        for (int i=0; i < length; i++)
            Array.set(array,i,filter(from,to,Array.get(array,i)));

        return array;
    }

    protected Object filterMap(ServerSession from, ServerChannel to, Map map)
    {
        if (map == null)
            return null;

        Iterator iter=map.entrySet().iterator();
        while(iter.hasNext())
        {
            Map.Entry entry=(Map.Entry)iter.next();
            entry.setValue(filter(from,to,entry.getValue()));
        }

        return map;
    }

    protected Object filterJSON(ServerSession from, ServerChannel to, JSON.Generator generator)
    {
        String json=JSON.toString(generator);
        Object data=JSON.parse(json);
        return filter(from,to,data);
    }

    protected Object filterJSON(ServerSession from, ServerChannel to, JSON.Literal json)
    {
        Object data=JSON.parse(json.toString());
        return filter(from,to,data);
    }

    protected Object filterObject(ServerSession from, ServerChannel to, Object obj)
    {
        return obj;
    }

}
