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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

public class OortLongMapTest extends AbstractOortObjectTest {
    public OortLongMapTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testShare() throws Exception {
        String name = "test";
        OortObject.Factory<ConcurrentMap<Long, Object>> factory = OortObjectFactories.forConcurrentMap();
        OortLongMap<Object> oortMap1 = new OortLongMap<>(oort1, name, factory);
        OortLongMap<Object> oortMap2 = new OortLongMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        final long key1 = 13L;
        final String value1 = "value1";
        final CountDownLatch objectLatch1 = new CountDownLatch(1);
        oortMap1.addListener(new OortObject.Listener<ConcurrentMap<Long, Object>>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<Long, Object>> oldInfo, OortObject.Info<ConcurrentMap<Long, Object>> newInfo) {
                Assert.assertTrue(newInfo.isLocal());
                Assert.assertNotNull(oldInfo);
                Assert.assertTrue(oldInfo.getObject().isEmpty());
                Assert.assertNotSame(oldInfo, newInfo);
                Assert.assertEquals(value1, newInfo.getObject().get(key1));
                objectLatch1.countDown();
            }
        });

        // The other OortObject listens to receive the object
        final CountDownLatch objectLatch2 = new CountDownLatch(1);
        oortMap2.addListener(new OortObject.Listener<ConcurrentMap<Long, Object>>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<Long, Object>> oldInfo, OortObject.Info<ConcurrentMap<Long, Object>> newInfo) {
                Assert.assertFalse(newInfo.isLocal());
                Assert.assertNotNull(oldInfo);
                Assert.assertTrue(oldInfo.getObject().isEmpty());
                Assert.assertNotSame(oldInfo, newInfo);
                Assert.assertEquals(value1, newInfo.getObject().get(key1));
                objectLatch2.countDown();
            }
        });

        // Change the object and share the change
        ConcurrentMap<Long, Object> object1 = factory.newObject(null);
        object1.put(key1, value1);
        oortMap1.setAndShare(object1, null);

        Assert.assertTrue(objectLatch1.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(objectLatch2.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(value1, oortMap1.getInfo(oort1.getURL()).getObject().get(key1));
        Assert.assertTrue(oortMap1.getInfo(oort2.getURL()).getObject().isEmpty());

        Assert.assertTrue(oortMap2.getInfo(oort2.getURL()).getObject().isEmpty());
        Assert.assertEquals(object1, oortMap2.getInfo(oort1.getURL()).getObject());

        ConcurrentMap<Long, Object> objectAtOort2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion());
        Assert.assertEquals(object1, objectAtOort2);
    }

    @Test
    public void testHowToDealWitMutableValues() throws Exception {
        // We are using a Map as mutable value because it serializes easily in JSON.
        // Any other mutable data structure would require a serializer/deserializer.

        String name = "test";
        OortObject.Factory<ConcurrentMap<Long, Map<String, Boolean>>> factory = OortObjectFactories.forConcurrentMap();
        OortLongMap<Map<String, Boolean>> oortMap1 = new OortLongMap<>(oort1, name, factory);
        OortLongMap<Map<String, Boolean>> oortMap2 = new OortLongMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        long key = 13L;
        Map<String, Boolean> node1Value = new HashMap<>();

        final CountDownLatch putLatch1 = new CountDownLatch(2);
        OortMap.EntryListener<Long, Map<String, Boolean>> listener1 = new OortMap.EntryListener<Long, Map<String, Boolean>>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<Long, Map<String, Boolean>>> info, OortMap.Entry<Long, Map<String, Boolean>> entry) {
                putLatch1.countDown();
            }
        };
        oortMap1.addEntryListener(listener1);
        oortMap2.addEntryListener(listener1);
        // First problem is how concurrent threads may insert the initial value for a certain key:
        // solution is to use putIfAbsentAndShare().
        OortObject.Result.Deferred<Map<String, Boolean>> result = new OortObject.Result.Deferred<>();
        oortMap1.putIfAbsentAndShare(key, node1Value, result);
        Map<String, Boolean> existing = result.get(5, TimeUnit.SECONDS);
        if (existing != null) {
            node1Value = existing;
        }

        Assert.assertTrue(putLatch1.await(5, TimeUnit.SECONDS));
        oortMap1.removeEntryListener(listener1);
        oortMap2.removeEntryListener(listener1);

        // Now we have a reference to the value object for that key.
        // We mutate the value object.
        synchronized (node1Value) {
            node1Value.put("1", true);
        }

        // Another thread may just get the value and modify it
        node1Value = oortMap1.get(key);
        synchronized (node1Value) {
            node1Value.put("2", true);
        }

        final CountDownLatch putLatch2 = new CountDownLatch(2);
        OortMap.EntryListener<Long, Map<String, Boolean>> listener2 = new OortMap.EntryListener<Long, Map<String, Boolean>>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<Long, Map<String, Boolean>>> info, OortMap.Entry<Long, Map<String, Boolean>> entry) {
                putLatch2.countDown();
            }
        };
        oortMap1.addEntryListener(listener2);
        oortMap2.addEntryListener(listener2);

        // Share the value notifying EntryListeners
        oortMap1.putAndShare(key, node1Value, null);
        Assert.assertTrue(putLatch2.await(5, TimeUnit.SECONDS));
        oortMap1.removeEntryListener(listener2);
        oortMap2.removeEntryListener(listener2);

        Map<String, Boolean> node2Value = oortMap2.find(key);
        Assert.assertEquals(2, node2Value.size());
    }
}
