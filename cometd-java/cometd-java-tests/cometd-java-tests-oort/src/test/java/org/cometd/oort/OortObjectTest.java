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
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class OortObjectTest extends AbstractOortObjectTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testShareObject(String serverTransport) throws Exception {
        prepare(serverTransport);

        String name = "test";
        OortObject.Factory<Map<String, Object>> factory = OortObjectFactories.forMap();
        OortObject<Map<String, Object>> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<Map<String, Object>> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        String key1 = "key1";
        String value1 = "value1";
        CountDownLatch objectLatch1 = new CountDownLatch(1);
        oortObject1.addListener(new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<Map<String, Object>> oldInfo, OortObject.Info<Map<String, Object>> newInfo) {
                Assertions.assertTrue(newInfo.isLocal());
                Assertions.assertNotNull(oldInfo);
                Assertions.assertTrue(oldInfo.getObject().isEmpty());
                Assertions.assertNotSame(oldInfo, newInfo);
                Assertions.assertEquals(value1, newInfo.getObject().get(key1));
                objectLatch1.countDown();
            }
        });

        // The other OortObject listens to receive the object
        CountDownLatch objectLatch2 = new CountDownLatch(1);
        oortObject2.addListener(new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<Map<String, Object>> oldInfo, OortObject.Info<Map<String, Object>> newInfo) {
                Assertions.assertFalse(newInfo.isLocal());
                Assertions.assertNotNull(oldInfo);
                Assertions.assertTrue(oldInfo.getObject().isEmpty());
                Assertions.assertNotSame(oldInfo, newInfo);
                Assertions.assertEquals(value1, newInfo.getObject().get(key1));
                objectLatch2.countDown();
            }
        });

        // Change the object and share the change
        Map<String, Object> object1 = factory.newObject(null);
        object1.put(key1, value1);
        oortObject1.setAndShare(object1, null);

        Assertions.assertTrue(objectLatch1.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(objectLatch2.await(5, TimeUnit.SECONDS));

        Assertions.assertEquals(value1, oortObject1.getInfo(oort1.getURL()).getObject().get(key1));
        Assertions.assertTrue(oortObject1.getInfo(oort2.getURL()).getObject().isEmpty());

        Assertions.assertTrue(oortObject2.getInfo(oort2.getURL()).getObject().isEmpty());
        Assertions.assertEquals(object1, oortObject2.getInfo(oort1.getURL()).getObject());

        Map<String, Object> objectAtOort2 = oortObject2.merge(OortObjectMergers.mapUnion());
        Assertions.assertEquals(object1, objectAtOort2);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testShareObjectWithAllChannelsSubscription(String serverTransport) throws Exception {
        prepare(serverTransport);

        String name = "test";
        OortObject.Factory<Map<String, Object>> factory = OortObjectFactories.forMap();
        OortObject<Map<String, Object>> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<Map<String, Object>> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        // Subscribe to /**, which is treated specially in Oort and Seti.
        oort1.observeChannel("/**");
        oort2.observeChannel("/**");
        // Wait a while to be sure to be subscribed.
        Thread.sleep(1000);

        String key1 = "key1";
        String value1 = "value1";

        // The other OortObject listens to receive the object.
        CountDownLatch objectLatch2 = new CountDownLatch(1);
        oortObject2.addListener(new OortObject.Listener<Map<String, Object>>() {
            @Override
            public void onUpdated(OortObject.Info<Map<String, Object>> oldInfo, OortObject.Info<Map<String, Object>> newInfo) {
                Assertions.assertEquals(value1, newInfo.getObject().get(key1));
                objectLatch2.countDown();
            }
        });

        // Set the object and share the change.
        Map<String, Object> object1 = factory.newObject(null);
        object1.put(key1, value1);
        oortObject1.setAndShare(object1, null);

        Assertions.assertTrue(objectLatch2.await(5, TimeUnit.SECONDS));

        Assertions.assertTrue(oortObject2.getInfo(oort2.getURL()).getObject().isEmpty());
        Assertions.assertEquals(object1, oortObject2.getInfo(oort1.getURL()).getObject());

        Map<String, Object> objectAtOort2 = oortObject2.merge(OortObjectMergers.mapUnion());
        Assertions.assertEquals(object1, objectAtOort2);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testLocalObjectIsPushedWhenNodeJoins(String serverTransport) throws Exception {
        prepare(serverTransport);

        String name = "test";
        OortObject.Factory<Map<String, Object>> factory = OortObjectFactories.forMap();
        OortObject<Map<String, Object>> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<Map<String, Object>> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        Map<String, Object> object1 = factory.newObject(null);
        String key1 = "key1";
        String value1 = "value1";
        object1.put(key1, value1);
        oortObject1.setAndShare(object1, null);

        Map<String, Object> object2 = factory.newObject(null);
        String key2 = "key2";
        String value2 = "value2";
        object2.put(key2, value2);
        oortObject2.setAndShare(object2, null);

        // Wait for shared objects to synchronize
        Thread.sleep(1000);

        // Connect node3
        Server server3 = startServer(serverTransport, 0);
        Oort oort3 = startOort(server3);
        OortComet oortComet31 = oort3.observeComet(oort1.getURL());
        Assertions.assertTrue(oortComet31.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet32 = oort3.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet32.waitFor(5000, BayeuxClient.State.CONNECTED));

        OortObject<Map<String, Object>> oortObject3 = new OortObject<>(oort3, name, factory);
        // Latch with 4 counts for node1 and node2 receiving from node3 + node3 receiving from node1 and node2.
        OortObjectInitialListener<Map<String, Object>> initialListener = new OortObjectInitialListener<>(4);
        oortObject1.addListener(initialListener);
        oortObject2.addListener(initialListener);
        oortObject3.addListener(initialListener);
        CometSubscriptionListener subscriptionListener = new CometSubscriptionListener(oortObject1.getChannelName(), 2);
        oort1.getBayeuxServer().addListener(subscriptionListener);
        oort2.getBayeuxServer().addListener(subscriptionListener);
        oortObject3.start();
        Assertions.assertTrue(subscriptionListener.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(initialListener.await(5, TimeUnit.SECONDS));

        Map<String, Object> object3 = factory.newObject(null);
        String key3 = "key3";
        String value3 = "value3";
        object3.put(key3, value3);
        CountDownLatch objectsLatch = new CountDownLatch(3);
        OortObject.Listener<Map<String, Object>> objectListener = new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<Map<String, Object>> oldInfo, OortObject.Info<Map<String, Object>> newInfo) {
                objectsLatch.countDown();
            }
        };
        oortObject1.addListener(objectListener);
        oortObject2.addListener(objectListener);
        oortObject3.addListener(objectListener);
        oortObject3.setAndShare(object3, null);
        Assertions.assertTrue(objectsLatch.await(5, TimeUnit.SECONDS));

        OortObject.Merger<Map<String, Object>, Map<String, Object>> merger = OortObjectMergers.mapUnion();
        Map<String, Object> objectAtOort1 = oortObject1.merge(merger);
        Assertions.assertEquals(3, objectAtOort1.size());
        Map<String, Object> objectAtOort2 = oortObject2.merge(merger);
        Assertions.assertEquals(3, objectAtOort2.size());
        Map<String, Object> objectAtOort3 = oortObject3.merge(merger);
        Assertions.assertEquals(3, objectAtOort3.size());

        oortObject3.stop();
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testLocalObjectIsRemovedWhenNodeLeaves(String serverTransport) throws Exception {
        prepare(serverTransport);

        String name = "test";
        OortObject.Factory<Map<String, Object>> factory = OortObjectFactories.forMap();
        OortObject<Map<String, Object>> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<Map<String, Object>> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        Map<String, Object> object1 = factory.newObject(null);
        String key1 = "key1";
        String value1 = "value1";
        object1.put(key1, value1);
        oortObject1.setAndShare(object1, null);

        Map<String, Object> object2 = factory.newObject(null);
        String key2 = "key2";
        String value2 = "value2";
        object2.put(key2, value2);
        oortObject2.setAndShare(object2, null);

        // Wait for shared objects to synchronize
        Thread.sleep(1000);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometLeftListener(latch));
        stopOort(oort1);
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        Map<String, Object> objectAtOort2 = oortObject2.merge(OortObjectMergers.mapUnion());
        Assertions.assertEquals(object2, objectAtOort2);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIterationOverInfos(String serverTransport) throws Exception {
        prepare(serverTransport);

        String name = "test";
        OortObject.Factory<Map<String, Object>> factory = OortObjectFactories.forMap();
        OortObject<Map<String, Object>> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<Map<String, Object>> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        Map<String, Object> object1 = factory.newObject(null);
        String key1 = "key1";
        String value1 = "value1";
        object1.put(key1, value1);
        oortObject1.setAndShare(object1, null);

        Map<String, Object> object2 = factory.newObject(null);
        String key2 = "key2";
        String value2 = "value2";
        object2.put(key2, value2);
        oortObject2.setAndShare(object2, null);

        // Wait for shared objects to synchronize
        Thread.sleep(1000);

        List<OortObject.Info<Map<String, Object>>> infos = new ArrayList<>();
        for (OortObject.Info<Map<String, Object>> info : oortObject1) {
            infos.add(info);
        }
        Assertions.assertEquals(2, infos.size());
        for (OortObject.Info<Map<String, Object>> info : infos) {
            Map<String, Object> data = info.getObject();
            if (data.containsKey(key1)) {
                Assertions.assertEquals(oort1.getURL(), info.getOortURL());
            } else if (data.containsKey(key2)) {
                Assertions.assertEquals(oort2.getURL(), info.getOortURL());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testNonCompositeObject(String serverTransport) throws Exception {
        prepare(serverTransport);

        String name = "test";
        OortObject.Factory<Long> factory = OortObjectFactories.forLong(0);
        OortObject<Long> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<Long> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        CountDownLatch latch1 = new CountDownLatch(2);
        OortObject.Listener<Long> objectListener1 = new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<Long> oldInfo, OortObject.Info<Long> newInfo) {
                latch1.countDown();
            }
        };
        oortObject1.addListener(objectListener1);
        oortObject2.addListener(objectListener1);
        long value1 = 2;
        oortObject1.setAndShare(value1, null);
        Assertions.assertTrue(latch1.await(5, TimeUnit.SECONDS));

        CountDownLatch latch2 = new CountDownLatch(2);
        OortObject.Listener<Long> objectListener2 = new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<Long> oldInfo, OortObject.Info<Long> newInfo) {
                latch2.countDown();
            }
        };
        oortObject1.addListener(objectListener2);
        oortObject2.addListener(objectListener2);
        long value2 = 3;
        oortObject2.setAndShare(value2, null);
        Assertions.assertTrue(latch2.await(5, TimeUnit.SECONDS));

        long sum = oortObject1.merge(OortObjectMergers.longSum());
        Assertions.assertEquals(value1 + value2, sum);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testStaleUpdateIsDiscarded(String serverTransport) throws Exception {
        prepare(serverTransport);

        String name = "test";
        OortObject.Factory<Long> factory = OortObjectFactories.forLong(0);
        OortObject<Long> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<Long> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        CountDownLatch latch1 = new CountDownLatch(2);
        OortObject.Listener<Long> objectListener1 = new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<Long> oldInfo, OortObject.Info<Long> newInfo) {
                latch1.countDown();
            }
        };
        oortObject1.addListener(objectListener1);
        oortObject2.addListener(objectListener1);
        long value1 = 1;
        oortObject1.setAndShare(value1, null);
        Assertions.assertTrue(latch1.await(5, TimeUnit.SECONDS));

        // Update "concurrently": the concurrence will be simulated
        long delay = 1000;
        long value2 = 2;
        long value3 = 3;
        oort2.getBayeuxServer().addExtension(new BayeuxServer.Extension() {
            @Override
            public boolean rcv(ServerSession from, ServerMessage.Mutable message) {
                if (oortObject1.getChannelName().equals(message.getChannel())) {
                    Map<String, Object> data = message.getDataAsMap();
                    if (data != null) {
                        if (value2 == ((Number)data.get(OortObject.Info.OBJECT_FIELD)).longValue()) {
                            new Thread(() -> {
                                try {
                                    Thread.sleep(delay);
                                    ServerChannel channel = oort2.getBayeuxServer().getChannel(message.getChannel());
                                    channel.publish(oort2.getOortSession(), message, Promise.noop());
                                } catch (InterruptedException ignored) {
                                }
                            }).start();
                            return false;
                        }
                    }
                }
                return true;
            }
        });

        OortObject.Result.Deferred<Long> result1 = new OortObject.Result.Deferred<>();
        oortObject1.setAndShare(value2, result1);
        Assertions.assertEquals(value1, result1.get(5, TimeUnit.SECONDS).longValue());

        OortObject.Result.Deferred<Long> result2 = new OortObject.Result.Deferred<>();
        oortObject1.setAndShare(value3, result2);
        Assertions.assertEquals(value2, result2.get(5, TimeUnit.SECONDS).longValue());

        Thread.sleep(2 * delay);

        long valueAtNode2 = oortObject2.getInfo(oort1.getURL()).getObject();
        Assertions.assertEquals(value3, valueAtNode2);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testConcurrent(String serverTransport) throws Exception {
        prepare(serverTransport);

        String name = "concurrent";
        OortObject.Factory<String> factory = OortObjectFactories.forString("");
        OortObject<String> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<String> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        BlockingQueue<String> values = new LinkedBlockingQueue<>();
        oortObject2.addListener(new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<String> oldInfo, OortObject.Info<String> newInfo) {
                String value = newInfo.getObject();
                values.offer(value);
            }
        });

        int threads = 64;
        int iterations = 32;
        CyclicBarrier barrier = new CyclicBarrier(threads + 1);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; ++i) {
            int index = i;
            new Thread(() -> {
                try {
                    barrier.await();
                    for (int j = 0; j < iterations; ++j) {
                        String value = "data_" + (index * iterations + j);
                        oortObject1.setAndShare(value, null);
                    }
                } catch (Throwable x) {
                    x.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        // Wait for all threads to be ready.
        barrier.await();

        // Wait for all threads to finish.
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Verify that the last value set (randomly) on the first OortObject is also
        // the same value on the second OortObject when all updates have arrived.
        String object1 = oortObject1.getInfo(oort1.getURL()).getObject();

        while (true) {
            String object2 = values.poll(10, TimeUnit.SECONDS);
            if (object2 == null) {
                logger.info(oortObject1.dump());
                logger.info(oortObject2.dump());
                Assertions.fail();
            } else {
                if (object2.equals(object1)) {
                    // Make sure there are no more values.
                    Assertions.assertNull(values.poll(1, TimeUnit.SECONDS));
                    break;
                }
            }
        }

        // Re-verify.
        String object2 = oortObject2.getInfo(oort1.getURL()).getObject();
        Assertions.assertEquals(object1, object2);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testApplicationLocking(String serverTransport) throws Exception {
        prepare(serverTransport);

        String name = "locking";
        OortObject.Factory<String> factory = OortObjectFactories.forString("initial");
        OortObject<String> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<String> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        ReentrantLock lock = new ReentrantLock();
        CountDownLatch updatedLatch = new CountDownLatch(1);
        oortObject1.addListener(new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<String> oldInfo, OortObject.Info<String> newInfo) {
                lock.lock();
                try {
                    updatedLatch.countDown();
                } finally {
                    lock.unlock();
                }
            }
        });

        OortObject.Result.Deferred<String> result = new OortObject.Result.Deferred<>();
        lock.lock();
        try {
            // We own the lock here.
            // Trigger the second OortObject to update the first, which
            // will cause an attempt to grab the lock in onUpdated();
            // the thread that calls onUpdated() is now blocked there.
            oortObject2.setAndShare("2", null);

            // Wait until the lock is attempted.
            while (true) {
                if (lock.hasQueuedThreads()) {
                    break;
                }
                Thread.sleep(1);
            }

            // Calling a blocking setAndShare() here would cause a deadlock.
            // We call the asynchronous version which will queue the operation
            // and return immediately, releasing the lock, so that the thread
            // blocked in onUpdated() can proceed.
            // oortObject1.setAndShare("1");
            oortObject1.setAndShare("1", result);
        } finally {
            lock.unlock();
        }

        Assertions.assertTrue(updatedLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertNotNull(result.get(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testNodeCrashWhileOtherNodeStarts(String serverTransport) throws Exception {
        prepare(serverTransport);

        String name = "crash";
        OortObject.Factory<String> factory = OortObjectFactories.forString("");

        Server server1 = startServer(serverTransport, 0);
        ServerConnector connector1 = (ServerConnector)server1.getConnectors()[0];
        int port1 = connector1.getLocalPort();
        oort1 = startOort(server1);
        OortObject<String> oortObject1 = new OortObject<>(oort1, name, factory);
        oortObject1.start();
        OortObject.Result.Deferred<String> result1 = new OortObject.Result.Deferred<>();
        oortObject1.setAndShare("data1", result1);
        result1.get(5, TimeUnit.SECONDS);

        // Simulate that node2 is starting but not yet ready to accept connections.
        Server server2 = startServer(serverTransport, 0);
        ServerConnector connector2 = (ServerConnector)server2.getConnectors()[0];
        int port2 = connector2.getLocalPort();
        connector2.stop();
        oort2 = startOort(server2);
        OortObject<String> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObject(oortObject2);
        OortObject.Result.Deferred<String> result2 = new OortObject.Result.Deferred<>();
        oortObject2.setAndShare("data2", result2);
        result2.get(5, TimeUnit.SECONDS);

        CountDownLatch joinedLatch = new CountDownLatch(1);
        CountDownLatch updateLatch2 = new CountDownLatch(1);
        oort1.addCometListener(new CometJoinedListener(joinedLatch));
        oortObject2.addListener(new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<String> oldInfo, OortObject.Info<String> newInfo) {
                updateLatch2.countDown();
            }
        });

        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertFalse(joinedLatch.await(1, TimeUnit.SECONDS));
        Assertions.assertFalse(updateLatch2.await(1, TimeUnit.SECONDS));

        // Simulate node1 crash.
        oortObject1.stop();
        stopOort(oort1);
        stopServer(server1);

        // Verify that node2 has no data from node1.
        OortObject.Info<String> info = oortObject2.getInfo(oort1.getURL());
        Assertions.assertNull(info);

        // Restart node1.
        server1 = startServer(serverTransport, port1);
        connector1 = (ServerConnector)server1.getConnectors()[0];
        connector1.stop();
        oort1 = startOort(server1);
        joinedLatch = new CountDownLatch(2);
        oort1.addCometListener(new CometJoinedListener(joinedLatch));
        oort2.addCometListener(new CometJoinedListener(joinedLatch));
        oortObject1 = new OortObject<>(oort1, name, factory);
        startOortObject(oortObject1);
        OortObject.Result.Deferred<String> result3 = new OortObject.Result.Deferred<>();
        oortObject1.setAndShare("data3", result3);
        result3.get(5, TimeUnit.SECONDS);
        CountDownLatch updateLatch1 = new CountDownLatch(1);
        oortObject1.addListener(new OortObject.Listener<>() {
            @Override
            public void onUpdated(OortObject.Info<String> oldInfo, OortObject.Info<String> newInfo) {
                updateLatch1.countDown();
            }
        });

        // Restore communication.
        connector1.setPort(port1);
        connector1.start();
        connector2.setPort(port2);
        connector2.start();

        Assertions.assertTrue(joinedLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(updateLatch1.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(updateLatch2.await(5, TimeUnit.SECONDS));
    }
}
