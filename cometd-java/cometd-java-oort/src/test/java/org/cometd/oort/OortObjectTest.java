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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.server.Server;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class OortObjectTest extends OortTest
{
    private Oort oort1;
    private Oort oort2;

    @Before
    public void prepare() throws Exception
    {
        Server server1 = startServer(0);
        oort1 = startOort(server1);
        Server server2 = startServer(0);
        oort2 = startOort(server2);

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
    }

    @Test
    public void testShareObject() throws Exception
    {
        String name = "test";
        OortObject.Factory<Map<String, Object>> factory = OortObjectFactories.forMap();
        OortObject<Map<String, Object>> oortObject1 = new OortObject<Map<String, Object>>(oort1, name, factory);
        OortObject<Map<String, Object>> oortObject2 = new OortObject<Map<String, Object>>(oort2, name, factory);

        // The other OortObject listens to receive the object
        final CountDownLatch objectLatch = new CountDownLatch(1);
        oortObject2.addListener(new OortObject.Listener.Adapter<Map<String, Object>>()
        {
            public void onUpdated(OortObject.Info<Map<String, Object>> oldInfo, OortObject.Info<Map<String, Object>> newInfo)
            {
                objectLatch.countDown();
            }
        });

        // Change the object and share the change
        Map<String, Object> object1 = oortObject1.getLocal();
        String key1 = "key1";
        String value1 = "value1";
        object1.put(key1, value1);
        oortObject1.share();

        Assert.assertTrue(objectLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(oortObject2.getLocal().isEmpty());
        Map<String, Object> object1AtOort2 = oortObject2.getRemote(oort1.getURL());
        Assert.assertEquals(object1, object1AtOort2);

        Map<String, Object> objectAtOort2 = oortObject2.get(OortObjectMergers.<String, Object>mapUnion());
        Assert.assertEquals(object1, objectAtOort2);
    }

    @Test
    public void testLocalObjectIsPushedWhenNodeJoins() throws Exception
    {
        String name = "test";
        OortObject.Factory<Map<String, Object>> factory = OortObjectFactories.forMap();
        OortObject<Map<String, Object>> oortObject1 = new OortObject<Map<String, Object>>(oort1, name, factory);
        OortObject<Map<String, Object>> oortObject2 = new OortObject<Map<String, Object>>(oort2, name, factory);

        Map<String, Object> object1 = oortObject1.getLocal();
        String key1 = "key1";
        String value1 = "value1";
        object1.put(key1, value1);
        oortObject1.share();

        Map<String, Object> object2 = oortObject2.getLocal();
        String key2 = "key2";
        String value2 = "value2";
        object2.put(key2, value2);
        oortObject2.share();

        // Wait for shared objects to synchronize
        Thread.sleep(1000);

        Server server3 = startServer(0);
        Oort oort3 = startOort(server3);
        CountDownLatch oortLatch = new CountDownLatch(2);
        CometJoinedListener listener = new CometJoinedListener(oortLatch);
        oort3.addCometListener(listener);
        OortComet oortComet31 = oort3.observeComet(oort1.getURL());
        Assert.assertTrue(oortComet31.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(oortLatch.await(5, TimeUnit.SECONDS));

        OortObject<Map<String, Object>> oortObject3 = new OortObject<Map<String, Object>>(oort3, name, factory);
        Map<String, Object> object3 = oortObject3.getLocal();
        String key3 = "key3";
        String value3 = "value3";
        object3.put(key3, value3);
        final CountDownLatch objectsLatch = new CountDownLatch(2);
        oortObject3.addListener(new OortObject.Listener.Adapter<Map<String, Object>>()
        {
            @Override
            public void onUpdated(OortObject.Info<Map<String, Object>> oldInfo, OortObject.Info<Map<String, Object>> newInfo)
            {
                objectsLatch.countDown();
            }
        });
        oortObject3.share();
        Assert.assertTrue(objectsLatch.await(5, TimeUnit.SECONDS));

        OortObject.Merger<Map<String, Object>> merger = OortObjectMergers.mapUnion();
        Map<String, Object> objectAtOort1 = oortObject1.get(merger);
        Assert.assertEquals(3, objectAtOort1.size());
        Map<String, Object> objectAtOort2 = oortObject2.get(merger);
        Assert.assertEquals(3, objectAtOort2.size());
        Map<String, Object> objectAtOort3 = oortObject3.get(merger);
        Assert.assertEquals(3, objectAtOort3.size());
    }

    @Test
    public void testLocalObjectIsRemovedWhenNodeLeaves() throws Exception
    {
        String name = "test";
        OortObject.Factory<Map<String, Object>> factory = OortObjectFactories.forMap();
        OortObject<Map<String, Object>> oortObject1 = new OortObject<Map<String, Object>>(oort1, name, factory);
        OortObject<Map<String, Object>> oortObject2 = new OortObject<Map<String, Object>>(oort2, name, factory);

        Map<String, Object> object1 = oortObject1.getLocal();
        String key1 = "key1";
        String value1 = "value1";
        object1.put(key1, value1);
        oortObject1.share();

        Map<String, Object> object2 = oortObject2.getLocal();
        String key2 = "key2";
        String value2 = "value2";
        object2.put(key2, value2);
        oortObject2.share();

        // Wait for shared objects to synchronize
        Thread.sleep(1000);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometLeftListener(latch));
        stopOort(oort1);
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        Map<String, Object> objectAtOort2 = oortObject2.get(OortObjectMergers.<String, Object>mapUnion());
        Assert.assertEquals(object2, objectAtOort2);
    }

    @Test
    public void testIterationOverInfos() throws Exception
    {
        String name = "test";
        OortObject.Factory<Map<String, Object>> factory = OortObjectFactories.forMap();
        OortObject<Map<String, Object>> oortObject1 = new OortObject<Map<String, Object>>(oort1, name, factory);
        OortObject<Map<String, Object>> oortObject2 = new OortObject<Map<String, Object>>(oort2, name, factory);

        Map<String, Object> object1 = oortObject1.getLocal();
        String key1 = "key1";
        String value1 = "value1";
        object1.put(key1, value1);
        oortObject1.share();

        Map<String, Object> object2 = oortObject2.getLocal();
        String key2 = "key2";
        String value2 = "value2";
        object2.put(key2, value2);
        oortObject2.share();

        // Wait for shared objects to synchronize
        Thread.sleep(1000);

        List<OortObject.Info<Map<String, Object>>> infos = new ArrayList<OortObject.Info<Map<String, Object>>>();
        for (OortObject.Info<Map<String, Object>> info : oortObject1)
            infos.add(info);
        Assert.assertEquals(2, infos.size());
        for (OortObject.Info<Map<String, Object>> info : infos)
        {
            Map<String, Object> data = info.getObject();
            if (data.containsKey(key1))
                Assert.assertEquals(oort1.getURL(), info.getOortURL());
            else if (data.containsKey(key2))
                Assert.assertEquals(oort2.getURL(), info.getOortURL());
        }
    }

    @Test
    public void testNonCompositeObject() throws Exception
    {
        String name = "test";
        OortObject.Factory<Long> factory = OortObjectFactories.forLong();
        CometSubscriptionListener subscriptionListener = new CometSubscriptionListener(OortObject.OORT_OBJECTS_CHANNEL, 2);
        oort1.getBayeuxServer().addListener(subscriptionListener);
        oort2.getBayeuxServer().addListener(subscriptionListener);
        OortObject<Long> oortObject1 = new OortObject<Long>(oort1, name, factory);
        OortObject<Long> oortObject2 = new OortObject<Long>(oort2, name, factory);
        Assert.assertTrue(subscriptionListener.await(5, TimeUnit.SECONDS));

        long value1 = 2;
        oortObject1.setLocal(value1);
        OortObjectTest.OortObjectInitialListener<Long> listener = new OortObjectTest.OortObjectInitialListener<Long>(2);
        oortObject1.addListener(listener);
        oortObject2.addListener(listener);
        oortObject1.share();
        Assert.assertTrue(listener.await(5, TimeUnit.SECONDS));

        long value2 = 3;
        oortObject2.setLocal(value2);

        final CountDownLatch latch = new CountDownLatch(1);
        oortObject1.addListener(new OortObject.Listener.Adapter<Long>()
        {
            @Override
            public void onUpdated(OortObject.Info<Long> oldInfo, OortObject.Info<Long> newInfo)
            {
                latch.countDown();
            }
        });
        oortObject2.share();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        long sum = oortObject1.get(OortObjectMergers.longSum());

        Assert.assertEquals(value1 + value2, sum);
    }

    public static class OortObjectInitialListener<T> extends OortObject.Listener.Adapter<T>
    {
        private final CountDownLatch latch;

        public OortObjectInitialListener(int parties)
        {
            this.latch = new CountDownLatch(parties);
        }

        @Override
        public void onUpdated(OortObject.Info<T> oldInfo, OortObject.Info<T> newInfo)
        {
            if (oldInfo == null)
                latch.countDown();
        }

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException
        {
            return latch.await(timeout, unit);
        }
    }
}
