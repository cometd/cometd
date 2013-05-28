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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.server.Server;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class OortListTest extends OortTest
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
    public void testElementAdded() throws Exception
    {
        String name = "test";
        OortObject.Factory<List<Long>> factory = OortObjectFactories.forConcurrentList();
        OortList<Long> oortList1 = new OortList<Long>(oort1, name, factory);
        OortList<Long> oortList2 = new OortList<Long>(oort2, name, factory);
        String channelName = oortList1.getChannelName();
        CometSubscriptionListener subscriptionListener = new CometSubscriptionListener(channelName, 2);
        oort1.getBayeuxServer().addListener(subscriptionListener);
        oort2.getBayeuxServer().addListener(subscriptionListener);
        oortList1.start();
        oortList2.start();
        Assert.assertTrue(subscriptionListener.await(5, TimeUnit.SECONDS));

        OortObjectTest.OortObjectInitialListener<List<Long>> listener = new OortObjectTest.OortObjectInitialListener<List<Long>>(2);
        oortList1.addListener(listener);
        oortList2.addListener(listener);
        oortList1.setAndShare(new CopyOnWriteArrayList<Long>());
        Assert.assertTrue(listener.await(5, TimeUnit.SECONDS));

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

        oortList1.addAndShare(element);

        Assert.assertTrue(addLatch.await(5, TimeUnit.SECONDS));

        oortList2.stop();
        oortList1.stop();
    }

    @Test
    public void testElementRemoved() throws Exception
    {
        String name = "test";
        OortObject.Factory<List<Long>> factory = OortObjectFactories.forConcurrentList();
        OortList<Long> oortList1 = new OortList<Long>(oort1, name, factory);
        OortList<Long> oortList2 = new OortList<Long>(oort2, name, factory);
        String channelName = oortList1.getChannelName();
        CometSubscriptionListener subscriptionListener = new CometSubscriptionListener(channelName, 2);
        oort1.getBayeuxServer().addListener(subscriptionListener);
        oort2.getBayeuxServer().addListener(subscriptionListener);
        oortList1.start();
        oortList2.start();
        Assert.assertTrue(subscriptionListener.await(5, TimeUnit.SECONDS));

        OortObjectTest.OortObjectInitialListener<List<Long>> listener = new OortObjectTest.OortObjectInitialListener<List<Long>>(2);
        oortList1.addListener(listener);
        oortList2.addListener(listener);
        List<Long> list = new CopyOnWriteArrayList<Long>();
        final long element = 1;
        list.add(element);
        oortList1.setAndShare(list);
        Assert.assertTrue(listener.await(5, TimeUnit.SECONDS));

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

        oortList1.removeAndShare(element);

        Assert.assertTrue(removeLatch.await(5, TimeUnit.SECONDS));

        oortList2.stop();
        oortList1.stop();
    }
}
