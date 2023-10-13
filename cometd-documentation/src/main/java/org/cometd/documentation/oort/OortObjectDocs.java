/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.documentation.oort;

import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.oort.Oort;
import org.cometd.oort.OortList;
import org.cometd.oort.OortLong;
import org.cometd.oort.OortObject;
import org.cometd.oort.OortObjectFactories;
import org.cometd.oort.OortObjectMergers;
import org.cometd.oort.OortStringMap;
import org.cometd.server.JettyJSONContextServer;
import org.eclipse.jetty.util.ajax.JSON;

@SuppressWarnings("unused")
public class OortObjectDocs {
    public static void factoryList(Oort oort) throws Exception {
        // tag::create[]
        // The factory for data entities.
        OortObject.Factory<List<String>> factory = OortObjectFactories.forConcurrentList();

        // Create the OortObject.
        OortObject<List<String>> users = new OortObject<>(oort, "users", factory);

        // Start it before using it.
        users.start();
        // end::create[]
    }

    // tag::shareList[]
    public void shareList(OortObject<List<String>> users) {
        OortObject.Factory<List<String>> factory = users.getFactory();

        // Create a "default" data entity.
        List<String> names = factory.newObject(null);

        // Fill it with data.
        names.add("B1");

        // Share the data entity with the other nodes.
        users.setAndShare(names, null);
    }
    // end::shareList[]

    // tag::useMap[]
    public void putAndShare(OortStringMap<UserInfo> userInfos) {
        // Map user name "B1" with its metadata.
        userInfos.putAndShare("B1", new UserInfo("B1"), null);
    }

    public void removeAndShare(OortStringMap<UserInfo> userInfos) {
        // Remove the mapping for user "B1".
        userInfos.removeAndShare("B1", null);
    }
    // end::useMap[]

    // tag::useList[]
    public void addAndShare(OortList<String> names) {
        // Add user name "B1"
        names.addAndShare(null, "B1");
    }

    public void removeAndShare(OortList<String> names) {
        // Remove user "B1"
        names.removeAndShare(null, "B1");
    }
    // end::useList[]

    private static class UserInfo {
        public UserInfo(String id) {
        }

        public String getId() {
            return null;
        }
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::clientJSON[]
    public class MyCustomJSONContextClient extends JettyJSONContextClient {
        public MyCustomJSONContextClient() {
            getJSON().addConvertor(UserInfo.class, new UserInfoConvertor());
        }
    }
    // end::clientJSON[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::clientConvertor[]
    public class UserInfoConvertor implements JSON.Convertor {
        @Override
        public void toJSON(Object obj, JSON.Output out) {
            UserInfo userInfo = (UserInfo)obj;
            out.addClass(UserInfo.class);
            out.add("id", userInfo.getId());
        }

        @Override
        public Object fromJSON(Map<String, Object> object) {
            String id = (String)object.get("id");
            return new UserInfo(id);
        }
    }
    // end::clientConvertor[]

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::serverJSON[]
    public class MyCustomJSONContextServer extends JettyJSONContextServer {
        public MyCustomJSONContextServer() {
            getJSON().addConvertor(UserInfo.class, new UserInfoConvertor());
        }
    }
    // end::serverJSON[]

    // tag::merge[]
    public List<String> merge(OortList<String> names) {
        // Merge all the names from all the parts.
        return names.merge(OortObjectMergers.listUnion());
    }
    // end::merge[]

    public void listener(Oort oort) throws Exception {
        // tag::listener[]
        // At initialization time, create the OortObject and add the listener.
        OortLong userCount = new OortLong(oort, "userCount");
        userCount.addListener(new OortObject.Listener<Long>() {
            @Override
            public void onUpdated(OortObject.Info<Long> oldInfo, OortObject.Info<Long> newInfo) {
                // The user count changed somewhere, broadcast the new value.
                long count = userCount.sum();
                broadcastUserCount(count);
            }

            @Override
            public void onRemoved(OortObject.Info<Long> info) {
                // A node disappeared, broadcast the new user count.
                long count = userCount.sum();
                broadcastUserCount(count);
            }

            private void broadcastUserCount(long count) {
                // Publish a message on "/user/count" to update the remote clients connected to this node
                BayeuxServer bayeuxServer = userCount.getOort().getBayeuxServer();
                bayeuxServer.getChannel("/user/count").publish(userCount.getLocalSession(), count, Promise.noop());
            }
        });
        userCount.start();
        // end::listener[]
    }
}
