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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.server.BayeuxServer;

public class OortList<E> extends OortObject<List<E>>
{
    public OortList(Oort oort, String name, List<E> initial)
    {
        super(oort, name, initial);
    }

    public void publishAdd(E item)
    {
        List<E> list = getLocal();
        if (!list.contains(item))
            throw new IllegalArgumentException("Item " + item + " is not an element of " + list);

        Map<String, Object> data = new HashMap<String, Object>();
        data.put(Info.OORT_URL_FIELD, getOort().getURL());
        data.put(Info.NAME_FIELD, getName());
        data.put(Info.OBJECT_FIELD, item);
        data.put(Info.TYPE_FIELD, "item");
        data.put(Info.ACTION_FIELD, "add");

        logger.debug("Cloud sharing list element {}", data);
        BayeuxServer bayeuxServer = getOort().getBayeuxServer();
        bayeuxServer.getChannel(OORT_OBJECTS_CHANNEL).publish(getLocalSession(), data, null);
    }

    public void publishRemove(E item)
    {
        throw new UnsupportedOperationException("Not Yet Implemented");
    }

    @Override
    protected void onObject(Map<String, Object> data)
    {
        if ("item".equals(data.get(Info.TYPE_FIELD)))
        {
            Info<List<E>> newInfo = getInfo((String)data.get(Info.OORT_URL_FIELD));
            List<E> list = newInfo.getObject();

            // Remember old data
            Info<List<E>> oldInfo = new Info<List<E>>(newInfo);
            oldInfo.put(Info.OBJECT_FIELD, new ArrayList<E>(list));

            // Handle item
            E item = (E)data.get(Info.OBJECT_FIELD);
            String action = (String)data.get(Info.ACTION_FIELD);
            if ("add".equals(action))
                list.add(item);
            else if ("remove".equals(action))
                list.remove(item);

            // TODO: notify just the element update...
            notifyOnUpdated(oldInfo, newInfo);
        }
        else
        {
            super.onObject(data);
        }
    }
}
