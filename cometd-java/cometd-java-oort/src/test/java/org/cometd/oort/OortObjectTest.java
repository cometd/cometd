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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.server.Server;
import org.junit.Assert;
import org.junit.Test;

public class OortObjectTest extends OortTest
{
    @Test
    public void testShareObject() throws Exception
    {
        Server server1 = startServer(0);
        final Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(2);
        CometJoinedListener listener = new CometJoinedListener(latch);
        oort1.addCometListener(listener);
        oort2.addCometListener(listener);
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertNotNull(oortComet21);
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait a while to be sure to be subscribed
        Thread.sleep(1000);

        String name = "test";
        OortObject<Map<String, Object>> oortObject1 = new OortObject<Map<String, Object>>(oort1, name);
        OortObject<Map<String, Object>> oortObject2 = new OortObject<Map<String, Object>>(oort2, name);

        String oortObjectsChannel = "/oort/objects";
        Set<ServerSession> s1 = oort1.getBayeuxServer().getChannel(oortObjectsChannel).getSubscribers();
        System.err.println("s1 = " + s1);
        List<ServerChannel.ServerChannelListener> l1 = oort1.getBayeuxServer().getChannel(oortObjectsChannel).getListeners();
        System.err.println("l1 = " + l1);
        Set<ServerSession> s2 = oort2.getBayeuxServer().getChannel(oortObjectsChannel).getSubscribers();
        System.err.println("s2 = " + s2);
        List<ServerChannel.ServerChannelListener> l2 = oort2.getBayeuxServer().getChannel(oortObjectsChannel).getListeners();
        System.err.println("l2 = " + l2);

        Map<String, Object> object1 = new HashMap<String, Object>();
        String key1 = "key1";
        String value1 = "value1";
        object1.put(key1, value1);
        oortObject1.setLocal(object1);
        oortObject1.publish();

        // Wait for the other OortObject to receive the object
        final CountDownLatch objectLatch = new CountDownLatch(1);
        oortObject2.addListener(new OortObject.Listener.Adapter<Map<String, Object>>()
        {
            public void onAdded(OortObject.MetaData<Map<String, Object>> metaData)
            {
                objectLatch.countDown();
            }
        });
        Assert.assertTrue(objectLatch.await(5, TimeUnit.SECONDS));

        Assert.assertNull(oortObject2.getLocal());
        Map<String, Object> object1AtOort2 = oortObject2.getRemote(oort1.getURL());
        Assert.assertEquals(object1, object1AtOort2);

        Map<String, Object> objectAtOort2 = oortObject2.get(new OortObject.UnionMergeStrategyMap<String, Object>());
        Assert.assertEquals(object1, objectAtOort2);
    }

    @Test
    public void testShareList() throws Exception
    {
        Server server1 = startServer(0);
        final Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(2);
        CometJoinedListener listener = new CometJoinedListener(latch);
        oort1.addCometListener(listener);
        oort2.addCometListener(listener);
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertNotNull(oortComet21);
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        String name = "test";
        OortObject<List<String>> oortList1 = new OortObject<List<String>>(oort1, name, new ArrayList<String>());
        List<String> list1 = oortList1.getLocal();
        synchronized (list1)
        {
            list1.add("a");
            list1.add("b");
        }
        oortList1.publish();

    }

}
