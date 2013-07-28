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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

public class OortListTest extends AbstractOortObjectTest
{
    public OortListTest(String serverTransport)
    {
        super(serverTransport);
    }

    @Test
    public void testElementAdded() throws Exception
    {
        String name = "test";
        OortObject.Factory<List<Long>> factory = OortObjectFactories.forConcurrentList();
        OortList<Long> oortList1 = new OortList<Long>(oort1, name, factory);
        OortList<Long> oortList2 = new OortList<Long>(oort2, name, factory);
        startOortObjects(oortList1, oortList2);

        final long element = 1;
        final CountDownLatch addLatch = new CountDownLatch(1);
        oortList2.addElementListener(new OortList.ElementListener.Adapter<Long>()
        {
            @Override
            public void onAdded(OortObject.Info<List<Long>> info, List<Long> elements)
            {
                Assert.assertEquals(1, elements.size());
                Assert.assertEquals(element, (long)elements.get(0));
                addLatch.countDown();
            }
        });

        Assert.assertTrue(oortList1.addAndShare(element));
        Assert.assertTrue(addLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testElementRemoved() throws Exception
    {
        String name = "test";
        OortObject.Factory<List<Long>> factory = OortObjectFactories.forConcurrentList();
        OortList<Long> oortList1 = new OortList<Long>(oort1, name, factory);
        OortList<Long> oortList2 = new OortList<Long>(oort2, name, factory);
        startOortObjects(oortList1, oortList2);

        final CountDownLatch setLatch = new CountDownLatch(2);
        OortObject.Listener<List<Long>> listener = new OortObject.Listener.Adapter<List<Long>>()
        {
            @Override
            public void onUpdated(OortObject.Info<List<Long>> oldInfo, OortObject.Info<List<Long>> newInfo)
            {
                setLatch.countDown();
            }
        };
        oortList1.addListener(listener);
        oortList2.addListener(listener);
        List<Long> list = factory.newObject(null);
        final long element = 1;
        list.add(element);
        oortList1.setAndShare(list);
        Assert.assertTrue(setLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch removeLatch = new CountDownLatch(1);
        oortList2.addElementListener(new OortList.ElementListener.Adapter<Long>()
        {
            @Override
            public void onRemoved(OortObject.Info<List<Long>> info, List<Long> elements)
            {
                Assert.assertEquals(1, elements.size());
                Assert.assertEquals(element, (long)elements.get(0));
                removeLatch.countDown();
            }
        });

        Assert.assertTrue(oortList1.removeAndShare(element));
        Assert.assertTrue(removeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDeltaListener() throws Exception
    {
        String name = "test";
        OortObject.Factory<List<String>> factory = OortObjectFactories.forConcurrentList();
        OortList<String> oortList1 = new OortList<String>(oort1, name, factory);
        OortList<String> oortList2 = new OortList<String>(oort2, name, factory);
        startOortObjects(oortList1, oortList2);

        final CountDownLatch setLatch1 = new CountDownLatch(2);
        OortObject.Listener<List<String>> listener = new OortObject.Listener.Adapter<List<String>>()
        {
            @Override
            public void onUpdated(OortObject.Info<List<String>> oldInfo, OortObject.Info<List<String>> newInfo)
            {
                setLatch1.countDown();
            }
        };
        oortList1.addListener(listener);
        oortList2.addListener(listener);
        List<String> oldList = factory.newObject(null);
        String elementA = "A";
        String elementB = "B";
        oldList.add(elementA);
        oldList.add(elementB);
        oortList1.setAndShare(oldList);
        Assert.assertTrue(setLatch1.await(5, TimeUnit.SECONDS));

        List<String> newList = factory.newObject(null);
        String elementC = "C";
        String elementD = "D";
        newList.add(elementA);
        newList.add(elementC);
        newList.add(elementD);

        final List<String> adds = new ArrayList<String>();
        final List<String> removes = new ArrayList<String>();
        final AtomicReference<CountDownLatch> setLatch2 = new AtomicReference<CountDownLatch>(new CountDownLatch(4));
        oortList1.addListener(new OortList.DeltaListener<String>(oortList1));
        oortList2.addListener(new OortList.DeltaListener<String>(oortList2));
        OortList.ElementListener<String> elementListener = new OortList.ElementListener<String>()
        {
            public void onAdded(OortObject.Info<List<String>> info, List<String> elements)
            {
                adds.addAll(elements);
                setLatch2.get().countDown();
            }

            public void onRemoved(OortObject.Info<List<String>> info, List<String> elements)
            {
                removes.addAll(elements);
                setLatch2.get().countDown();
            }
        };
        oortList1.addElementListener(elementListener);
        oortList2.addElementListener(elementListener);
        oortList1.setAndShare(newList);

        Assert.assertTrue(setLatch2.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(4, adds.size());
        Assert.assertEquals(2, removes.size());

        adds.clear();
        removes.clear();
        setLatch2.set(new CountDownLatch(1));
        // Stop Oort1 so that OortList2 gets the notification
        stopOort(oort1);

        Assert.assertTrue(setLatch2.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(3, removes.size());
    }

    @Test
    public void testContains() throws Exception
    {
        String name = "test";
        OortObject.Factory<List<String>> factory = OortObjectFactories.forConcurrentList();
        OortList<String> oortList1 = new OortList<String>(oort1, name, factory);
        OortList<String> oortList2 = new OortList<String>(oort2, name, factory);
        startOortObjects(oortList1, oortList2);

        final CountDownLatch setLatch = new CountDownLatch(2);
        OortObject.Listener<List<String>> listener = new OortObject.Listener.Adapter<List<String>>()
        {
            @Override
            public void onUpdated(OortObject.Info<List<String>> oldInfo, OortObject.Info<List<String>> newInfo)
            {
                setLatch.countDown();
            }
        };
        oortList1.addListener(listener);
        oortList2.addListener(listener);
        oortList1.setAndShare(factory.newObject(null));
        Assert.assertTrue(setLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch addLatch = new CountDownLatch(4);
        OortList.ElementListener<String> addedListener = new OortList.ElementListener.Adapter<String>()
        {
            @Override
            public void onAdded(OortObject.Info<List<String>> info, List<String> elements)
            {
                addLatch.countDown();
            }
        };
        oortList1.addElementListener(addedListener);
        oortList2.addElementListener(addedListener);
        String element1A = "1A";
        oortList1.addAndShare(element1A);
        String element2A = "2A";
        oortList2.addAndShare(element2A);
        Assert.assertTrue(addLatch.await(5, TimeUnit.SECONDS));

        Assert.assertTrue(oortList1.contains(element1A));
        Assert.assertFalse(oortList2.contains(element1A));
        Assert.assertFalse(oortList1.contains(element2A));
        Assert.assertTrue(oortList2.contains(element2A));

        Assert.assertTrue(oortList1.isPresent(element1A));
        Assert.assertTrue(oortList2.isPresent(element1A));
        Assert.assertTrue(oortList1.isPresent(element2A));
        Assert.assertTrue(oortList2.isPresent(element2A));

        oortList2.removeElementListener(addedListener);
        oortList1.removeElementListener(addedListener);
    }
}
