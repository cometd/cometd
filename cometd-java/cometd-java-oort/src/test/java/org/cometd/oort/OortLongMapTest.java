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

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

public class OortLongMapTest extends AbstractOortObjectTest
{
    @Test
    public void testShare() throws Exception
    {
        String name = "test";
        OortObject.Factory<ConcurrentMap<Long, Object>> factory = OortObjectFactories.forConcurrentMap();
        OortLongMap<Object> oortMap1 = new OortLongMap<Object>(oort1, name, factory);
        OortLongMap<Object> oortMap2 = new OortLongMap<Object>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        final long key1 = 13L;
        final String value1 = "value1";
        final CountDownLatch objectLatch1 = new CountDownLatch(1);
        oortMap1.addListener(new OortObject.Listener.Adapter<ConcurrentMap<Long, Object>>()
        {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<Long, Object>> oldInfo, OortObject.Info<ConcurrentMap<Long, Object>> newInfo)
            {
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
        oortMap2.addListener(new OortObject.Listener.Adapter<ConcurrentMap<Long, Object>>()
        {
            public void onUpdated(OortObject.Info<ConcurrentMap<Long, Object>> oldInfo, OortObject.Info<ConcurrentMap<Long, Object>> newInfo)
            {
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
        oortMap1.setAndShare(object1);

        Assert.assertTrue(objectLatch1.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(objectLatch2.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(value1, oortMap1.getInfo(oort1.getURL()).getObject().get(key1));
        Assert.assertTrue(oortMap1.getInfo(oort2.getURL()).getObject().isEmpty());

        Assert.assertTrue(oortMap2.getInfo(oort2.getURL()).getObject().isEmpty());
        Assert.assertEquals(object1, oortMap2.getInfo(oort1.getURL()).getObject());

        ConcurrentMap<Long, Object> objectAtOort2 = oortMap2.merge(OortObjectMergers.<Long, Object>concurrentMapUnion());
        Assert.assertEquals(object1, objectAtOort2);
    }
}
