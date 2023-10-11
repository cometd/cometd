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
import org.cometd.server.AbstractServerTransport;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class OortStringMapTest extends AbstractOortObjectTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testEntryPut(Transport transport) throws Exception {
        prepare(transport);

        String name = "test";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        CountDownLatch setLatch = new CountDownLatch(2);
        OortObject.Listener<ConcurrentMap<String, String>> objectListener = new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<String, String>> oldInfo, OortObject.Info<ConcurrentMap<String, String>> newInfo) {
                setLatch.countDown();
            }
        };
        oortMap1.addListener(objectListener);
        oortMap2.addListener(objectListener);
        String key = "key";
        String value1 = "value1";
        ConcurrentMap<String, String> map = factory.newObject(null);
        map.put(key, value1);
        oortMap1.setAndShare(map, null);
        Assertions.assertTrue(setLatch.await(5, TimeUnit.SECONDS));

        String value2 = "value2";
        CountDownLatch putLatch = new CountDownLatch(1);
        oortMap2.addEntryListener(new OortMap.EntryListener<>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                Assertions.assertEquals(key, entry.getKey());
                Assertions.assertEquals(value1, entry.getOldValue());
                Assertions.assertEquals(value2, entry.getNewValue());
                putLatch.countDown();
            }
        });

        OortObject.Result.Deferred<String> result = new OortObject.Result.Deferred<>();
        oortMap1.putAndShare(key, value2, result);
        Assertions.assertEquals(value1, result.get(5, TimeUnit.SECONDS));

        Assertions.assertTrue(putLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testEntryRemoved(Transport transport) throws Exception {
        prepare(transport);

        String name = "test";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        CountDownLatch setLatch = new CountDownLatch(2);
        OortObject.Listener<ConcurrentMap<String, String>> objectListener = new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<String, String>> oldInfo, OortObject.Info<ConcurrentMap<String, String>> newInfo) {
                setLatch.countDown();
            }
        };
        oortMap1.addListener(objectListener);
        oortMap2.addListener(objectListener);
        String key = "key";
        String value1 = "value1";
        ConcurrentMap<String, String> map = factory.newObject(null);
        map.put(key, value1);
        oortMap1.setAndShare(map, null);
        Assertions.assertTrue(setLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch removeLatch = new CountDownLatch(1);
        oortMap2.addEntryListener(new OortMap.EntryListener<>() {
            @Override
            public void onRemoved(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                Assertions.assertEquals(key, entry.getKey());
                Assertions.assertEquals(value1, entry.getOldValue());
                Assertions.assertNull(entry.getNewValue());
                removeLatch.countDown();
            }
        });

        OortObject.Result.Deferred<String> result = new OortObject.Result.Deferred<>();
        oortMap1.removeAndShare(key, result);
        Assertions.assertEquals(value1, result.get(5, TimeUnit.SECONDS));
        Assertions.assertTrue(removeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDeltaListener(Transport transport) throws Exception {
        prepare(transport);

        String name = "test";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        CountDownLatch setLatch1 = new CountDownLatch(2);
        OortObject.Listener<ConcurrentMap<String, String>> objectListener = new OortObject.Listener<>() {
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
        Assertions.assertTrue(setLatch1.await(5, TimeUnit.SECONDS));

        ConcurrentMap<String, String> newMap = factory.newObject(null);
        String valueA2 = "valueA2";
        newMap.put(key1, valueA2);
        String key3 = "key3";
        String valueC = "valueC";
        newMap.put(key3, valueC);

        List<OortMap.Entry<String, String>> puts = new ArrayList<>();
        List<OortMap.Entry<String, String>> removes = new ArrayList<>();
        AtomicReference<CountDownLatch> setLatch2 = new AtomicReference<>(new CountDownLatch(6));
        oortMap1.addListener(new OortMap.DeltaListener<>(oortMap1));
        oortMap2.addListener(new OortMap.DeltaListener<>(oortMap2));
        OortMap.EntryListener<String, String> entryListener = new OortMap.EntryListener<>() {
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

        Assertions.assertTrue(setLatch2.get().await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(4, puts.size());
        Assertions.assertEquals(2, removes.size());

        puts.clear();
        removes.clear();
        setLatch2.set(new CountDownLatch(2));
        // Stop Oort1 so that OortMap2 gets the notification
        stopOort(oort1);

        Assertions.assertTrue(setLatch2.get().await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(2, removes.size());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testGetFind(Transport transport) throws Exception {
        prepare(transport);

        String name = "test";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        CountDownLatch putLatch = new CountDownLatch(4);
        OortMap.EntryListener<String, String> putListener = new OortMap.EntryListener<>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                putLatch.countDown();
            }
        };
        oortMap1.addEntryListener(putListener);
        oortMap2.addEntryListener(putListener);
        String keyA = "keyA";
        String valueA = "valueA";
        oortMap1.putAndShare(keyA, valueA, null);
        String keyB = "keyB";
        String valueB = "valueB";
        oortMap2.putAndShare(keyB, valueB, null);
        Assertions.assertTrue(putLatch.await(5, TimeUnit.SECONDS));

        Assertions.assertEquals(valueA, oortMap1.get(keyA));
        Assertions.assertNull(oortMap1.get(keyB));
        Assertions.assertEquals(valueB, oortMap2.get(keyB));
        Assertions.assertNull(oortMap2.get(keyA));

        Assertions.assertEquals(valueA, oortMap1.find(keyA));
        Assertions.assertEquals(valueA, oortMap2.find(keyA));
        Assertions.assertEquals(valueB, oortMap1.find(keyB));
        Assertions.assertEquals(valueB, oortMap2.find(keyB));

        OortObject.Info<ConcurrentMap<String, String>> info1A = oortMap1.findInfo(keyA);
        Assertions.assertNotNull(info1A);
        Assertions.assertTrue(info1A.isLocal());
        Assertions.assertEquals(oort1.getURL(), info1A.getOortURL());
        OortObject.Info<ConcurrentMap<String, String>> info1B = oortMap1.findInfo(keyB);
        Assertions.assertNotNull(info1B);
        Assertions.assertFalse(info1B.isLocal());
        Assertions.assertEquals(oort2.getURL(), info1B.getOortURL());

        oortMap2.removeEntryListener(putListener);
        oortMap1.removeEntryListener(putListener);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testConcurrent(Transport transport) throws Exception {
        prepare(transport);

        String name = "concurrent";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        int threads = 64;
        int iterations = 32;
        CyclicBarrier barrier = new CyclicBarrier(threads + 1);
        CountDownLatch latch1 = new CountDownLatch(threads);
        CountDownLatch latch2 = new CountDownLatch(threads * iterations);

        oortMap2.addListener(new OortMap.DeltaListener<>(oortMap2));
        oortMap2.addEntryListener(new OortMap.EntryListener<>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                latch2.countDown();
            }
        });

        for (int i = 0; i < threads; ++i) {
            int index = i;
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
        Assertions.assertTrue(latch1.await(15, TimeUnit.SECONDS));
        Assertions.assertTrue(latch2.await(15, TimeUnit.SECONDS));

        ConcurrentMap<String, String> map1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion());
        ConcurrentMap<String, String> map2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion());
        Assertions.assertEquals(map1, map2);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testEntryBeforeMap(Transport transport) throws Exception {
        prepare(transport);

        String name = "entry_before_map";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);
        // Simulate that the second map lost the whole map update from the first map.
        oortMap2.removeInfo(oort1.getURL());

        Assertions.assertNull(oortMap2.getInfo(oort1.getURL()));

        CountDownLatch objectLatch = new CountDownLatch(1);
        oortMap2.addListener(new OortObject.Listener<>() {
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

        Assertions.assertTrue(objectLatch.await(5, TimeUnit.SECONDS));

        ConcurrentMap<String, String> map1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion());
        ConcurrentMap<String, String> map2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion());
        Assertions.assertEquals(map1, map2);

        AtomicReference<CountDownLatch> putLatch = new AtomicReference<>(new CountDownLatch(1));
        oortMap2.addEntryListener(new OortMap.EntryListener<>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                putLatch.get().countDown();
            }
        });

        // Put another entry in oortMap1, the objects should sync.
        OortObject.Result.Deferred<String> result2 = new OortObject.Result.Deferred<>();
        oortMap1.putAndShare("key2", "value2", result2);
        result2.get(5, TimeUnit.SECONDS);

        Assertions.assertTrue(putLatch.get().await(5, TimeUnit.SECONDS));

        map1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion());
        map2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion());
        Assertions.assertEquals(map1, map2);

        // And again.
        putLatch.set(new CountDownLatch(1));
        OortObject.Result.Deferred<String> result3 = new OortObject.Result.Deferred<>();
        oortMap1.putAndShare("key3", "value3", result3);
        result3.get(5, TimeUnit.SECONDS);

        Assertions.assertTrue(putLatch.get().await(5, TimeUnit.SECONDS));

        map1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion());
        map2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion());
        Assertions.assertEquals(map1, map2);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testLostEntry(Transport transport) throws Exception {
        prepare(transport);

        String name = "lost_entry";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory) {
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

        String key1 = "key1";
        OortObject.Result.Deferred<String> result1 = new OortObject.Result.Deferred<>();
        oortMap1.putAndShare(key1, "value1", result1);
        result1.get(5, TimeUnit.SECONDS);
        oortMap1.removeAndShare(key1, null);

        // Wait for the update to be lost.
        Thread.sleep(1000);

        // Verify that the objects are out-of-sync.
        Assertions.assertNull(oortMap1.get(key1));
        Assertions.assertNotNull(oortMap2.find(key1));

        // Update again, the maps should sync.
        String key2 = "key2";
        CountDownLatch latch = new CountDownLatch(2);
        oortMap2.addListener(new OortMap.DeltaListener<>(oortMap2));
        oortMap2.addEntryListener(new OortMap.EntryListener<>() {
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

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Make sure that the maps are in sync.
        ConcurrentMap<String, String> map1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion());
        ConcurrentMap<String, String> map2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion());
        Assertions.assertEquals(map1, map2);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testNodeSyncWithLargeMap(Transport transport) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("ws.maxMessageSize", String.valueOf(64 * 1024 * 1024));
        prepare(transport, options);

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
        Assertions.assertTrue(leftLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(comet12.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assertions.assertTrue(comet21.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // Update node1 with a large number of map entries.
        int size = 64 * 1024;
        for (int i = 0; i < size; ++i) {
            oortMap1.putAndShare(String.valueOf(i), i + "_abcdefghijklmnopqrstuvwxyz0123456789", null);
        }

        int size1 = oortMap1.merge(OortObjectMergers.concurrentMapUnion()).size();
        Assertions.assertEquals(size, size1);
        int size2 = oortMap2.merge(OortObjectMergers.concurrentMapUnion()).size();
        Assertions.assertEquals(0, size2);

        CountDownLatch syncLatch = new CountDownLatch(1);
        oortMap2.addListener(new OortObject.Listener<>() {
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
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(joinedLatch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertNotNull(oortComet21);
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the maps to sync.
        Assertions.assertTrue(syncLatch.await(15, TimeUnit.SECONDS));

        // Verify that the maps are in sync.
        size1 = oortMap1.getInfo(oort1.getURL()).getObject().size();
        size2 = oortMap2.getInfo(oort1.getURL()).getObject().size();
        Assertions.assertEquals(size1, size2);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testNodeHalfDisconnectedWillReconnect(Transport transport) throws Exception {
        long timeout = 5000;
        long maxInterval = 3000;
        Map<String, String> options = new HashMap<>();
        options.put("timeout", String.valueOf(timeout));
        options.put("maxInterval", String.valueOf(maxInterval));
        prepare(transport, options);
        String name = "half_disconnection";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory);
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        CountDownLatch putLatch = new CountDownLatch(4);
        OortMap.EntryListener<String, String> putListener = new OortMap.EntryListener<>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                putLatch.countDown();
            }
        };
        oortMap1.addEntryListener(putListener);
        oortMap2.addEntryListener(putListener);
        oortMap1.putAndShare("key1", "value1", null);
        oortMap2.putAndShare("key2", "value2", null);
        Assertions.assertTrue(putLatch.await(5, TimeUnit.SECONDS));

        // Stop only one of the connectors, so that the communication is half-disconnected.
        CountDownLatch leftLatch = new CountDownLatch(2);
        oortMap1.getOort().addCometListener(new CometLeftListener(leftLatch));
        oortMap1.addListener(new OortObject.Listener<>() {
            @Override
            public void onRemoved(OortObject.Info<ConcurrentMap<String, String>> info) {
                leftLatch.countDown();
            }
        });
        Server server1 = (Server)oortMap1.getOort().getBayeuxServer().getOption(Server.class.getName());
        ServerConnector connector1 = (ServerConnector)server1.getConnectors()[0];
        int port1 = connector1.getLocalPort();
        connector1.stop();
        Assertions.assertTrue(leftLatch.await(2 * (timeout + maxInterval), TimeUnit.MILLISECONDS));

        // Give some time before reconnecting.
        Thread.sleep(1000);

        CountDownLatch joinLatch = new CountDownLatch(2);
        oortMap1.getOort().addCometListener(new CometJoinedListener(joinLatch));
        oortMap1.addListener(new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<String, String>> oldInfo, OortObject.Info<ConcurrentMap<String, String>> newInfo) {
                if (oldInfo == null) {
                    joinLatch.countDown();
                }
            }
        });
        connector1.setPort(port1);
        connector1.start();
        Assertions.assertTrue(joinLatch.await(15, TimeUnit.SECONDS));

        String value2 = oortMap1.find("key2");
        Assertions.assertNotNull(value2);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testNodeHalfDisconnectedWillResync(Transport transport) throws Exception {
        long timeout = 5000;
        long maxInterval = 3000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        prepare(transport, options);
        String name = "half_disconnection_resync";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        String key2B = "key2B";
        AtomicReference<ConcurrentMap<String, String>> mergeRef = new AtomicReference<>();
        CountDownLatch mergeLatch = new CountDownLatch(1);
        OortStringMap<String> oortMap1 = new OortStringMap<>(oort1, name, factory) {
            @Override
            protected void onObject(Map<String, Object> data) {
                @SuppressWarnings("unchecked")
                Map<String, String> map = (Map<String, String>)data.get(Info.OBJECT_FIELD);
                if (map != null && map.containsValue(key2B)) {
                    // We have a Part for node2, but not an Info,
                    // try a merge - should only contain node1 entries.
                    mergeRef.set(merge(OortObjectMergers.concurrentMapUnion()));
                    mergeLatch.countDown();
                }
                super.onObject(data);
            }
        };
        OortStringMap<String> oortMap2 = new OortStringMap<>(oort2, name, factory);
        startOortObjects(oortMap1, oortMap2);

        CountDownLatch putLatch = new CountDownLatch(4);
        OortMap.EntryListener<String, String> putListener = new OortMap.EntryListener<>() {
            @Override
            public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                putLatch.countDown();
            }
        };
        oortMap1.addEntryListener(putListener);
        oortMap2.addEntryListener(putListener);
        oortMap1.putAndShare("key1A", "value1A", null);
        oortMap2.putAndShare("key2A", "value2A", null);
        Assertions.assertTrue(putLatch.await(5, TimeUnit.SECONDS));

        // Stop only one of the connectors, so that the communication is half-disconnected.
        CountDownLatch leftLatch = new CountDownLatch(2);
        oortMap1.getOort().addCometListener(new CometLeftListener(leftLatch));
        oortMap1.addListener(new OortObject.Listener<>() {
            @Override
            public void onRemoved(OortObject.Info<ConcurrentMap<String, String>> info) {
                leftLatch.countDown();
            }
        });
        Server server1 = (Server)oortMap1.getOort().getBayeuxServer().getOption(Server.class.getName());
        ServerConnector connector1 = (ServerConnector)server1.getConnectors()[0];
        int port1 = connector1.getLocalPort();
        connector1.stop();
        Assertions.assertTrue(leftLatch.await(2 * (timeout + maxInterval), TimeUnit.MILLISECONDS));

        // OortCometBA communication is interrupted, but OortCometAB
        // will allow nodeA to receive messages from nodeB.
        oortMap2.putAndShare("key2B", "value2B", null);

        // The merge should only contain node1 entries.
        Assertions.assertTrue(mergeLatch.await(5, TimeUnit.SECONDS));
        ConcurrentMap<String, String> merge = mergeRef.get();
        Assertions.assertEquals(1, merge.size());
        Assertions.assertEquals("value1A", merge.get("key1A"));

        // Give some time before reconnecting.
        Thread.sleep(1000);

        CountDownLatch joinLatch = new CountDownLatch(2);
        oortMap1.getOort().addCometListener(new CometJoinedListener(joinLatch));
        oortMap1.addListener(new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<ConcurrentMap<String, String>> oldInfo, OortObject.Info<ConcurrentMap<String, String>> newInfo) {
                if (oldInfo == null) {
                    joinLatch.countDown();
                }
            }
        });
        connector1.setPort(port1);
        connector1.start();
        Assertions.assertTrue(joinLatch.await(15, TimeUnit.SECONDS));

        String value2A = oortMap1.find("key2A");
        Assertions.assertNotNull(value2A);
        String value2B = oortMap1.find("key2B");
        Assertions.assertNotNull(value2B);
        String value1A = oortMap2.find("key1A");
        Assertions.assertNotNull(value1A);
    }
}
