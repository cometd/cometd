/*
 * Copyright (c) 2010 the original author or authors.
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
        data.put(MetaData.OORT_URL_FIELD, getOort().getURL());
        data.put(MetaData.NAME_FIELD, getName());
        data.put(MetaData.OBJECT_FIELD, item);
        data.put(MetaData.TYPE_FIELD, "item");
        data.put(MetaData.ACTION_FIELD, "add");

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
        if ("item".equals(data.get(MetaData.TYPE_FIELD)))
        {
            MetaData<List<E>> newMetaData = getMetaData((String)data.get(MetaData.OORT_URL_FIELD));
            List<E> list = newMetaData.getObject();

            // Remember old data
            Map<String, Object> oldData = newMetaData.asMap();
            oldData.put(MetaData.OBJECT_FIELD, new ArrayList<E>(list));
            MetaData<List<E>> oldMetaData = new MetaData<List<E>>(oldData);

            // Handle item
            E item = (E)data.get(MetaData.OBJECT_FIELD);
            String action = (String)data.get(MetaData.ACTION_FIELD);
            if ("add".equals(action))
                list.add(item);
            else if ("remove".equals(action))
                list.remove(item);

            notifyOnUpdated(oldMetaData, newMetaData);
        }
        else
        {
            super.onObject(data);
        }
    }
}
