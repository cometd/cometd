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

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.server.Server;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class OortMapTest extends OortTest
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
    public void testEntryPut() throws Exception
    {
        String name = "test";
        String channelName = OortObject.OORT_OBJECTS_CHANNEL + "/" + name;
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        CometSubscriptionListener subscriptionListener = new CometSubscriptionListener(channelName, 2);
        oort1.getBayeuxServer().addListener(subscriptionListener);
        oort2.getBayeuxServer().addListener(subscriptionListener);
        OortMap<String, String> oortMap1 = new OortMap<String, String>(oort1, name, factory);
        oortMap1.start();
        OortMap<String, String> oortMap2 = new OortMap<String, String>(oort2, name, factory);
        oortMap2.start();
        Assert.assertTrue(subscriptionListener.await(5, TimeUnit.SECONDS));

        OortObjectTest.OortObjectInitialListener<ConcurrentMap<String, String>> listener = new OortObjectTest.OortObjectInitialListener<ConcurrentMap<String, String>>(2);
        oortMap1.addListener(listener);
        oortMap2.addListener(listener);
        oortMap1.share();
        Assert.assertTrue(listener.await(5, TimeUnit.SECONDS));

        final String key = "key";
        final String value = "value";
        final CountDownLatch putLatch = new CountDownLatch(1);
        oortMap2.addEntryListener(new OortMap.EntryListener.Adapter<String, String>()
        {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, Map.Entry<String, String> entry)
            {
                Assert.assertEquals(key, entry.getKey());
                Assert.assertEquals(value, entry.getValue());
                putLatch.countDown();
            }
        });

        oortMap1.putAndShare(key, value);

        Assert.assertTrue(putLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testEntryRemoved() throws Exception
    {
        String name = "test";
        String channelName = OortObject.OORT_OBJECTS_CHANNEL + "/" + name;
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        CometSubscriptionListener subscriptionListener = new CometSubscriptionListener(channelName, 2);
        oort1.getBayeuxServer().addListener(subscriptionListener);
        oort2.getBayeuxServer().addListener(subscriptionListener);
        OortMap<String, String> oortMap1 = new OortMap<String, String>(oort1, name, factory);
        oortMap1.start();
        OortMap<String, String> oortMap2 = new OortMap<String, String>(oort2, name, factory);
        oortMap2.start();
        Assert.assertTrue(subscriptionListener.await(5, TimeUnit.SECONDS));

        final String key = "key";
        final String value = "value";
        oortMap1.getLocal().put(key, value);

        OortObjectTest.OortObjectInitialListener<ConcurrentMap<String, String>> listener = new OortObjectTest.OortObjectInitialListener<ConcurrentMap<String, String>>(2);
        oortMap1.addListener(listener);
        oortMap2.addListener(listener);
        oortMap1.share();
        Assert.assertTrue(listener.await(5, TimeUnit.SECONDS));

        final CountDownLatch removeLatch = new CountDownLatch(1);
        oortMap2.addEntryListener(new OortMap.EntryListener.Adapter<String, String>()
        {
            @Override
            public void onRemoved(OortObject.Info<ConcurrentMap<String, String>> info, Map.Entry<String, String> entry)
            {
                Assert.assertEquals(key, entry.getKey());
                removeLatch.countDown();
            }
        });

        oortMap1.removeAndShare(key);

        Assert.assertTrue(removeLatch.await(5, TimeUnit.SECONDS));
    }
}
