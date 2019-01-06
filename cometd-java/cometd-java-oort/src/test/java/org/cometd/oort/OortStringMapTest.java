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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.Assert;
import org.junit.Test;

public class OortStringMapTest extends AbstractOortObjectTest {
    public OortStringMapTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testEntryPut() throws Exception {
        String name = "test";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        final CountDownLatch setLatch = new CountDownLatch(2);
        OortObject.Listener<ConcurrentMap<String, String>> objectListener = new OortObject.Listener.Adapter<ConcurrentMap<String, String>>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<String, String>> oldInfo, OortObject.Info<ConcurrentMap<String, String>> newInfo) {
                setLatch.countDown();
            }
        };
        oortMap1.addListener(objectListener);
        oortMap2.addListener(objectListener);
        final String key = "key";
        final String value1 = "value1";
        ConcurrentMap<String, String> map = factory.newObject(null);
        map.put(key, value1);
        oortMap1.setAndShare(map, null);
        Assert.assertTrue(setLatch.await(5, TimeUnit.SECONDS));

        final String value2 = "value2";
        final CountDownLatch putLatch = new CountDownLatch(1);
        oortMap2.addEntryListener(new OortMap.EntryListener.Adapter<String, String>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                Assert.assertEquals(key, entry.getKey());
                Assert.assertEquals(value1, entry.getOldValue());
                Assert.assertEquals(value2, entry.getNewValue());
                putLatch.countDown();
            }
        });

        OortObject.Result.Deferred<String> result = new OortObject.Result.Deferred<>();
        oortMap1.putAndShare(key, value2, result);
        Assert.assertEquals(value1, result.get(5, TimeUnit.SECONDS));

        Assert.assertTrue(putLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testEntryRemoved() throws Exception {
        String name = "test";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        final CountDownLatch setLatch = new CountDownLatch(2);
        OortObject.Listener<ConcurrentMap<String, String>> objectListener = new OortObject.Listener.Adapter<ConcurrentMap<String, String>>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<String, String>> oldInfo, OortObject.Info<ConcurrentMap<String, String>> newInfo) {
                setLatch.countDown();
            }
        };
        oortMap1.addListener(objectListener);
        oortMap2.addListener(objectListener);
        final String key = "key";
        final String value1 = "value1";
        ConcurrentMap<String, String> map = factory.newObject(null);
        map.put(key, value1);
        oortMap1.setAndShare(map, null);
        Assert.assertTrue(setLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch removeLatch = new CountDownLatch(1);
        oortMap2.addEntryListener(new OortMap.EntryListener.Adapter<String, String>() {
            @Override
            public void onRemoved(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                Assert.assertEquals(key, entry.getKey());
                Assert.assertEquals(value1, entry.getOldValue());
                Assert.assertNull(entry.getNewValue());
                removeLatch.countDown();
            }
        });

        OortObject.Result.Deferred<String> result = new OortObject.Result.Deferred<>();
        oortMap1.removeAndShare(key, result);
        Assert.assertEquals(value1, result.get(5, TimeUnit.SECONDS));
        Assert.assertTrue(removeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDeltaListener() throws Exception {
        String name = "test";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        final CountDownLatch setLatch1 = new CountDownLatch(2);
        OortObject.Listener<ConcurrentMap<String, String>> objectListener = new OortObject.Listener.Adapter<ConcurrentMap<String, String>>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<String, String>> oldInfo, OortObject.Info<ConcurrentMap<String, String>> newInfo) {
                setLatch1.countDown();
            }
        };
        oortMap1.addListener(objectListener);
        oortMap2.addListener(objectListener);
        ConcurrentMap<String, String> oldMap = factory.newObject(null);
        String key1 = "key1";
        String valueA1 = "valueA1";
        oldMap.put(key1, valueA1);
        String key2 = "key2";
        String valueB = "valueB";
        oldMap.put(key2, valueB);
        oortMap1.setAndShare(oldMap, null);
        Assert.assertTrue(setLatch1.await(5, TimeUnit.SECONDS));

        ConcurrentMap<String, String> newMap = factory.newObject(null);
        String valueA2 = "valueA2";
        newMap.put(key1, valueA2);
        String key3 = "key3";
        String valueC = "valueC";
        newMap.put(key3, valueC);

        final List<OortMap.Entry<String, String>> puts = new ArrayList<>();
        final List<OortMap.Entry<String, String>> removes = new ArrayList<>();
        final AtomicReference<CountDownLatch> setLatch2 = new AtomicReference<>(new CountDownLatch(6));
        oortMap1.addListener(new OortMap.DeltaListener<>(oortMap1));
        oortMap2.addListener(new OortMap.DeltaListener<>(oortMap2));
        OortMap.EntryListener<String, String> entryListener = new OortMap.EntryListener<String, String>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                puts.add(entry);
                setLatch2.get().countDown();
            }

            @Override
            public void onRemoved(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                removes.add(entry);
                setLatch2.get().countDown();
            }
        };
        oortMap1.addEntryListener(entryListener);
        oortMap2.addEntryListener(entryListener);
        oortMap1.setAndShare(newMap, null);

        Assert.assertTrue(setLatch2.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(4, puts.size());
        Assert.assertEquals(2, removes.size());

        puts.clear();
        removes.clear();
        setLatch2.set(new CountDownLatch(2));
        // Stop Oort1 so that OortMap2 gets the notification
        stopOort(oort1);

        Assert.assertTrue(setLatch2.get().await(5, TimeUnit.SECONDS));
        Assert.assertEquals(2, removes.size());
    }

    @Test
    public void testGetFind() throws Exception {
        String name = "test";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        final CountDownLatch putLatch = new CountDownLatch(4);
        OortMap.EntryListener.Adapter<String, String> putListener = new OortMap.EntryListener.Adapter<String, String>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                putLatch.countDown();
            }
        };
        oortMap1.addEntryListener(putListener);
        oortMap2.addEntryListener(putListener);
        final String keyA = "keyA";
        final String valueA = "valueA";
        oortMap1.putAndShare(keyA, valueA, null);
        final String keyB = "keyB";
        final String valueB = "valueB";
        oortMap2.putAndShare(keyB, valueB, null);
        Assert.assertTrue(putLatch.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(valueA, oortMap1.get(keyA));
        Assert.assertNull(oortMap1.get(keyB));
        Assert.assertEquals(valueB, oortMap2.get(keyB));
        Assert.assertNull(oortMap2.get(keyA));

        Assert.assertEquals(valueA, oortMap1.find(keyA));
        Assert.assertEquals(valueA, oortMap2.find(keyA));
        Assert.assertEquals(valueB, oortMap1.find(keyB));
        Assert.assertEquals(valueB, oortMap2.find(keyB));

        OortObject.Info<ConcurrentMap<String, String>> info1A = oortMap1.findInfo(keyA);
        Assert.assertNotNull(info1A);
        Assert.assertTrue(info1A.isLocal());
        Assert.assertEquals(oort1.getURL(), info1A.getOortURL());
        OortObject.Info<ConcurrentMap<String, String>> info1B = oortMap1.findInfo(keyB);
        Assert.assertNotNull(info1B);
        Assert.assertFalse(info1B.isLocal());
        Assert.assertEquals(oort2.getURL(), info1B.getOortURL());

        oortMap2.removeEntryListener(putListener);
        oortMap1.removeEntryListener(putListener);
    }

    @Test
    public void testConcurrent() throws Exception {
        String name = "concurrent";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        final OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        int threads = 64;
        final int iterations = 32;
        final CyclicBarrier barrier = new CyclicBarrier(threads + 1);
        final CountDownLatch latch1 = new CountDownLatch(threads);
        final CountDownLatch latch2 = new CountDownLatch(threads * iterations);

        oortMap2.addListener(new OortMap.DeltaListener<>(oortMap2));
        oortMap2.addEntryListener(new OortMap.EntryListener.Adapter<String, String>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                latch2.countDown();
            }
        });

        for (int i = 0; i < threads; ++i) {
            final int index = i;
            new Thread(() -> {
                try {
                    barrier.await();
                    for (int j = 0; j < iterations; ++j) {
                        String key = String.valueOf(index * iterations + j);
                        oortMap1.putAndShare(key, key, null);
                    }
                } catch (Throwable x) {
                    x.printStackTrace();
                } finally {
                    latch1.countDown();
                }
            }).start();
        }
        // Wait for all threads to be ready.
        barrier.await();

        // Wait for all threads to finish.
        Assert.assertTrue(latch1.await(15, TimeUnit.SECONDS));
        Assert.assertTrue(latch2.await(15, TimeUnit.SECONDS));

        ConcurrentMap<String, String> map1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion());
        ConcurrentMap<String, String> map2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion());
        Assert.assertEquals(map1, map2);
    }

    @Test
    public void testEntryBeforeMap() throws Exception {
        String name = "entry_before_map";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);
        // Simulate that the second map lost the whole map update from the first map.
        oortMap2.removeInfo(oort1.getURL());

        Assert.assertNull(oortMap2.getInfo(oort1.getURL()));

        final CountDownLatch objectLatch = new CountDownLatch(1);
        oortMap2.addListener(new OortObject.Listener.Adapter<ConcurrentMap<String, String>>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<String, String>> oldInfo, OortObject.Info<ConcurrentMap<String, String>> newInfo) {
                objectLatch.countDown();
            }
        });

        // Put an entry in oortMap1, the update should arrive to oortMap2
        // which does not have the Info object, so it should pull it.
        OortObject.Result.Deferred<String> result1 = new OortObject.Result.Deferred<>();
        oortMap1.putAndShare("key1", "value1", result1);
        result1.get(5, TimeUnit.SECONDS);

        Assert.assertTrue(objectLatch.await(5, TimeUnit.SECONDS));

        ConcurrentMap<String, String> map1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion());
        ConcurrentMap<String, String> map2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion());
        Assert.assertEquals(map1, map2);

        final AtomicReference<CountDownLatch> putLatch = new AtomicReference<>(new CountDownLatch(1));
        oortMap2.addEntryListener(new OortMap.EntryListener.Adapter<String, String>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                putLatch.get().countDown();
            }
        });

        // Put another entry in oortMap1, the objects should sync.
        OortObject.Result.Deferred<String> result2 = new OortObject.Result.Deferred<>();
        oortMap1.putAndShare("key2", "value2", result2);
        result2.get(5, TimeUnit.SECONDS);

        Assert.assertTrue(putLatch.get().await(5, TimeUnit.SECONDS));

        map1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion());
        map2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion());
        Assert.assertEquals(map1, map2);

        // And again.
        putLatch.set(new CountDownLatch(1));
        OortObject.Result.Deferred<String> result3 = new OortObject.Result.Deferred<>();
        oortMap1.putAndShare("key3", "value3", result3);
        result3.get(5, TimeUnit.SECONDS);

        Assert.assertTrue(putLatch.get().await(5, TimeUnit.SECONDS));

        map1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion());
        map2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion());
        Assert.assertEquals(map1, map2);
    }

    @Test
    public void testLostEntry() throws Exception {
        String name = "lost_entry";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        final OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<String>(oort2, name, factory) {
            private int peerMessages;

            @Override
            protected void onObject(Map<String, Object> data) {
                String oortURL = (String)data.get(Info.OORT_URL_FIELD);
                if (!getOort().getURL().equals(oortURL)) {
                    if ("oort.map.entry".equals(data.get(Info.TYPE_FIELD))) {
                        ++peerMessages;
                        // Simulate that the second entry update gets lost.
                        if (peerMessages == 2) {
                            return;
                        }
                    }
                }
                super.onObject(data);
            }
        };
        startOortObjects(oortMap1, oortMap2);

        final String key1 = "key1";
        OortObject.Result.Deferred<String> result1 = new OortObject.Result.Deferred<>();
        oortMap1.putAndShare(key1, "value1", result1);
        result1.get(5, TimeUnit.SECONDS);
        oortMap1.removeAndShare(key1, null);

        // Wait for the update to be lost.
        Thread.sleep(1000);

        // Verify that the objects are out-of-sync.
        Assert.assertNull(oortMap1.get(key1));
        Assert.assertNotNull(oortMap2.find(key1));

        // Update again, the maps should sync.
        final String key2 = "key2";
        final CountDownLatch latch = new CountDownLatch(2);
        oortMap2.addListener(new OortMap.DeltaListener<>(oortMap2));
        oortMap2.addEntryListener(new OortMap.EntryListener<String, String>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                if (entry.getKey().equals(key2)) {
                    latch.countDown();
                }
            }

            @Override
            public void onRemoved(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                if (entry.getKey().equals(key1)) {
                    latch.countDown();
                }
            }
        });
        oortMap1.putAndShare(key2, "value2", null);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Make sure that the maps are in sync.
        ConcurrentMap<String, String> map1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion());
        ConcurrentMap<String, String> map2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion());
        Assert.assertEquals(map1, map2);
    }

    @Test
    public void testNodeSyncWithLargeMap() throws Exception {
        // Reconfigure the Oorts.
        stop();
        Map<String, String> options = new HashMap<>();
        options.put("ws.maxMessageSize", String.valueOf(64 * 1024 * 1024));
        prepare(options);

        String name = "large_sync";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        // Disconnect one node.
        CountDownLatch leftLatch = new CountDownLatch(2);
        CometLeftListener leftListener = new CometLeftListener(leftLatch);
        oort1.addCometListener(leftListener);
        oort2.addCometListener(leftListener);
        OortComet comet12 = oort1.findComet(oort2.getURL());
        OortComet comet21 = oort2.findComet(oort1.getURL());
        comet21.disconnect();
        Assert.assertTrue(leftLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(comet12.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assert.assertTrue(comet21.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // Update node1 with a large number of map entries.
        final int size = 64 * 1024;
        for (int i = 0; i < size; ++i) {
            oortMap1.putAndShare(String.valueOf(i), i + "_abcdefghijklmnopqrstuvwxyz0123456789", null);
        }

        int size1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion()).size();
        Assert.assertEquals(size, size1);
        int size2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion()).size();
        Assert.assertEquals(0, size2);

        final CountDownLatch syncLatch = new CountDownLatch(1);
        oortMap2.addListener(new OortObject.Listener.Adapter<ConcurrentMap<String, String>>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<String, String>> oldInfo, OortObject.Info<ConcurrentMap<String, String>> newInfo) {
                if (newInfo.getOortURL().equals(oort1.getURL())) {
                    if (newInfo.getObject().size() == size) {
                        syncLatch.countDown();
                    }
                }
            }
        });

        // Reconnect the node.
        CountDownLatch joinedLatch = new CountDownLatch(2);
        CometJoinedListener joinedListener = new CometJoinedListener(joinedLatch);
        oort1.addCometListener(joinedListener);
        oort2.addCometListener(joinedListener);
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(joinedLatch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertNotNull(oortComet21);
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the maps to sync.
        Assert.assertTrue(syncLatch.await(15, TimeUnit.SECONDS));

        // Verify that the maps are in sync.
        size1 = oortMap1.getInfo(oort1.getURL()).getObject().size();
        size2 = oortMap2.getInfo(oort1.getURL()).getObject().size();
        Assert.assertEquals(size1, size2);
    }

    @Test
    public void testNodeHalfDisconnected() throws Exception {
        stop();
        long timeout = 5000;
        long maxInterval = 3000;
        Map<String, String> options = new HashMap<>();
        options.put("timeout", String.valueOf(timeout));
        options.put("maxInterval", String.valueOf(maxInterval));
        prepare(options);
        String name = "half_disconnection";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        final OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        final CountDownLatch putLatch = new CountDownLatch(4);
        OortMap.EntryListener<String, String> putListener = new OortMap.EntryListener.Adapter<String, String>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                putLatch.countDown();
            }
        };
        oortMap1.addEntryListener(putListener);
        oortMap2.addEntryListener(putListener);
        oortMap1.putAndShare("key1", "value1", null);
        oortMap2.putAndShare("key2", "value2", null);
        Assert.assertTrue(putLatch.await(5, TimeUnit.SECONDS));

        // Stop only one of the connectors, so that the communication is half-disconnected.
        final CountDownLatch leftLatch = new CountDownLatch(2);
        oortMap1.getOort().addCometListener(new CometLeftListener(leftLatch));
        oortMap1.addListener(new OortObject.Listener.Adapter<ConcurrentMap<String, String>>() {
            @Override
            public void onRemoved(OortObject.Info<ConcurrentMap<String, String>> info) {
                leftLatch.countDown();
            }
        });
        Server server1 = (Server)oortMap1.getOort().getBayeuxServer().getOption(Server.class.getName());
        ServerConnector connector1 = (ServerConnector)server1.getConnectors()[0];
        int port1 = connector1.getLocalPort();
        connector1.stop();
        Assert.assertTrue(leftLatch.await(2 * (timeout + maxInterval), TimeUnit.SECONDS));

        // Give some time before reconnecting.
        Thread.sleep(1000);

        final CountDownLatch joinLatch = new CountDownLatch(2);
        oortMap1.getOort().addCometListener(new CometJoinedListener(joinLatch));
        oortMap1.addListener(new OortObject.Listener.Adapter<ConcurrentMap<String, String>>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<String, String>> oldInfo, OortObject.Info<ConcurrentMap<String, String>> newInfo) {
                if (oldInfo == null) {
                    joinLatch.countDown();
                }
            }
        });
        connector1.setPort(port1);
        connector1.start();
        Assert.assertTrue(joinLatch.await(15, TimeUnit.SECONDS));

        String value2 = oortMap1.find("key2");
        Assert.assertNotNull(value2);
    }
}
