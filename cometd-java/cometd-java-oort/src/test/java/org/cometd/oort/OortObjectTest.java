/*
 * Copyright (c) 2008-2017 the original author or authors.
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

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.server.Server;
import org.junit.Assert;
import org.junit.Test;

public class OortObjectTest extends AbstractOortObjectTest {
    public OortObjectTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testShareObject() throws Exception {
        String name = "test";
        OortObject.Factory<Map<String, Object>> factory = OortObjectFactories.forMap();
        OortObject<Map<String, Object>> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<Map<String, Object>> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        final String key1 = "key1";
        final String value1 = "value1";
        final CountDownLatch objectLatch1 = new CountDownLatch(1);
        oortObject1.addListener(new OortObject.Listener.Adapter<Map<String, Object>>() {
            @Override
            public void onUpdated(OortObject.Info<Map<String, Object>> oldInfo, OortObject.Info<Map<String, Object>> newInfo) {
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
        oortObject2.addListener(new OortObject.Listener.Adapter<Map<String, Object>>() {
            @Override
            public void onUpdated(OortObject.Info<Map<String, Object>> oldInfo, OortObject.Info<Map<String, Object>> newInfo) {
                Assert.assertFalse(newInfo.isLocal());
                Assert.assertNotNull(oldInfo);
                Assert.assertTrue(oldInfo.getObject().isEmpty());
                Assert.assertNotSame(oldInfo, newInfo);
                Assert.assertEquals(value1, newInfo.getObject().get(key1));
                objectLatch2.countDown();
            }
        });

        // Change the object and share the change
        Map<String, Object> object1 = factory.newObject(null);
        object1.put(key1, value1);
        oortObject1.setAndShare(object1, null);

        Assert.assertTrue(objectLatch1.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(objectLatch2.await(5, TimeUnit.SECONDS));

        Assert.assertEquals(value1, oortObject1.getInfo(oort1.getURL()).getObject().get(key1));
        Assert.assertTrue(oortObject1.getInfo(oort2.getURL()).getObject().isEmpty());

        Assert.assertTrue(oortObject2.getInfo(oort2.getURL()).getObject().isEmpty());
        Assert.assertEquals(object1, oortObject2.getInfo(oort1.getURL()).getObject());

        Map<String, Object> objectAtOort2 = oortObject2.merge(OortObjectMergers.mapUnion());
        Assert.assertEquals(object1, objectAtOort2);
    }

    @Test
    public void testLocalObjectIsPushedWhenNodeJoins() throws Exception {
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
        Server server3 = startServer(0);
        Oort oort3 = startOort(server3);
        OortComet oortComet31 = oort3.observeComet(oort1.getURL());
        Assert.assertTrue(oortComet31.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet32 = oort3.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet32.waitFor(5000, BayeuxClient.State.CONNECTED));

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
        Assert.assertTrue(subscriptionListener.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(initialListener.await(5, TimeUnit.SECONDS));

        Map<String, Object> object3 = factory.newObject(null);
        String key3 = "key3";
        String value3 = "value3";
        object3.put(key3, value3);
        final CountDownLatch objectsLatch = new CountDownLatch(3);
        OortObject.Listener.Adapter<Map<String, Object>> objectListener = new OortObject.Listener.Adapter<Map<String, Object>>() {
            @Override
            public void onUpdated(OortObject.Info<Map<String, Object>> oldInfo, OortObject.Info<Map<String, Object>> newInfo) {
                objectsLatch.countDown();
            }
        };
        oortObject1.addListener(objectListener);
        oortObject2.addListener(objectListener);
        oortObject3.addListener(objectListener);
        oortObject3.setAndShare(object3, null);
        Assert.assertTrue(objectsLatch.await(5, TimeUnit.SECONDS));

        OortObject.Merger<Map<String, Object>, Map<String, Object>> merger = OortObjectMergers.mapUnion();
        Map<String, Object> objectAtOort1 = oortObject1.merge(merger);
        Assert.assertEquals(3, objectAtOort1.size());
        Map<String, Object> objectAtOort2 = oortObject2.merge(merger);
        Assert.assertEquals(3, objectAtOort2.size());
        Map<String, Object> objectAtOort3 = oortObject3.merge(merger);
        Assert.assertEquals(3, objectAtOort3.size());

        oortObject3.stop();
    }

    @Test
    public void testLocalObjectIsRemovedWhenNodeLeaves() throws Exception {
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
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        Map<String, Object> objectAtOort2 = oortObject2.merge(OortObjectMergers.mapUnion());
        Assert.assertEquals(object2, objectAtOort2);
    }

    @Test
    public void testIterationOverInfos() throws Exception {
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
        Assert.assertEquals(2, infos.size());
        for (OortObject.Info<Map<String, Object>> info : infos) {
            Map<String, Object> data = info.getObject();
            if (data.containsKey(key1)) {
                Assert.assertEquals(oort1.getURL(), info.getOortURL());
            } else if (data.containsKey(key2)) {
                Assert.assertEquals(oort2.getURL(), info.getOortURL());
            }
        }
    }

    @Test
    public void testNonCompositeObject() throws Exception {
        String name = "test";
        OortObject.Factory<Long> factory = OortObjectFactories.forLong(0);
        OortObject<Long> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<Long> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        final CountDownLatch latch1 = new CountDownLatch(2);
        OortObject.Listener<Long> objectListener1 = new OortObject.Listener.Adapter<Long>() {
            @Override
            public void onUpdated(OortObject.Info<Long> oldInfo, OortObject.Info<Long> newInfo) {
                latch1.countDown();
            }
        };
        oortObject1.addListener(objectListener1);
        oortObject2.addListener(objectListener1);
        long value1 = 2;
        oortObject1.setAndShare(value1, null);
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));

        final CountDownLatch latch2 = new CountDownLatch(2);
        OortObject.Listener<Long> objectListener2 = new OortObject.Listener.Adapter<Long>() {
            @Override
            public void onUpdated(OortObject.Info<Long> oldInfo, OortObject.Info<Long> newInfo) {
                latch2.countDown();
            }
        };
        oortObject1.addListener(objectListener2);
        oortObject2.addListener(objectListener2);
        long value2 = 3;
        oortObject2.setAndShare(value2, null);
        Assert.assertTrue(latch2.await(5, TimeUnit.SECONDS));

        long sum = oortObject1.merge(OortObjectMergers.longSum());
        Assert.assertEquals(value1 + value2, sum);
    }

    @Test
    public void testStaleUpdateIsDiscarded() throws Exception {
        String name = "test";
        OortObject.Factory<Long> factory = OortObjectFactories.forLong(0);
        final OortObject<Long> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<Long> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        final CountDownLatch latch1 = new CountDownLatch(2);
        OortObject.Listener<Long> objectListener1 = new OortObject.Listener.Adapter<Long>() {
            @Override
            public void onUpdated(OortObject.Info<Long> oldInfo, OortObject.Info<Long> newInfo) {
                latch1.countDown();
            }
        };
        oortObject1.addListener(objectListener1);
        oortObject2.addListener(objectListener1);
        final long value1 = 1;
        oortObject1.setAndShare(value1, null);
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));

        // Update "concurrently": the concurrence will be simulated
        final long delay = 1000;
        final long value2 = 2;
        final long value3 = 3;
        oort2.getBayeuxServer().addExtension(new BayeuxServer.Extension.Adapter() {
            @Override
            public boolean rcv(ServerSession from, final ServerMessage.Mutable message) {
                if (oortObject1.getChannelName().equals(message.getChannel())) {
                    Map<String, Object> data = message.getDataAsMap();
                    if (data != null) {
                        if (value2 == ((Number)data.get(OortObject.Info.OBJECT_FIELD)).longValue()) {
                            new Thread() {
                                @Override
                                public void run() {
                                    try {
                                        sleep(delay);
                                        ServerChannel channel = oort2.getBayeuxServer().getChannel(message.getChannel());
                                        channel.publish(oort2.getOortSession(), message);
                                    } catch (InterruptedException ignored) {
                                    }
                                }
                            }.start();
                            return false;
                        }
                    }
                }
                return true;
            }
        });

        OortObject.Result.Deferred<Long> result1 = new OortObject.Result.Deferred<>();
        oortObject1.setAndShare(value2, result1);
        Assert.assertEquals(value1, result1.get(5, TimeUnit.SECONDS).longValue());

        OortObject.Result.Deferred<Long> result2 = new OortObject.Result.Deferred<>();
        oortObject1.setAndShare(value3, result2);
        Assert.assertEquals(value2, result2.get(5, TimeUnit.SECONDS).longValue());

        Thread.sleep(2 * delay);

        long valueAtNode2 = oortObject2.getInfo(oort1.getURL()).getObject();
        Assert.assertEquals(value3, valueAtNode2);
    }

    @Test
    public void testConcurrent() throws Exception {
        String name = "concurrent";
        OortObject.Factory<String> factory = OortObjectFactories.forString("");
        final OortObject<String> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<String> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        final BlockingQueue<String> values = new LinkedBlockingQueue<>();
        oortObject2.addListener(new OortObject.Listener.Adapter<String>() {
            @Override
            public void onUpdated(OortObject.Info<String> oldInfo, OortObject.Info<String> newInfo) {
                String value = newInfo.getObject();
                values.offer(value);
            }
        });

        int threads = 64;
        final int iterations = 32;
        final CyclicBarrier barrier = new CyclicBarrier(threads + 1);
        final CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; ++i) {
            final int index = i;
            new Thread(new Runnable() {
                @Override
                public void run() {
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
                }
            }).start();
        }
        // Wait for all threads to be ready.
        barrier.await();

        // Wait for all threads to finish.
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Verify that the last value set (randomly) on the first OortObject is also
        // the same value on the second OortObject when all updates have arrived.
        String object1 = oortObject1.getInfo(oort1.getURL()).getObject();

        while (true) {
            String object2 = values.poll(10, TimeUnit.SECONDS);
            if (object2.equals(object1)) {
                // Make sure there are no more values.
                Assert.assertNull(values.poll(1, TimeUnit.SECONDS));
                break;
            }
        }

        // Re-verify.
        String object2 = oortObject2.getInfo(oort1.getURL()).getObject();
        Assert.assertEquals(object1, object2);
    }

    @Test(timeout = 5000)
    public void testApplicationLocking() throws Exception {
        String name = "locking";
        OortObject.Factory<String> factory = OortObjectFactories.forString("initial");
        OortObject<String> oortObject1 = new OortObject<>(oort1, name, factory);
        OortObject<String> oortObject2 = new OortObject<>(oort2, name, factory);
        startOortObjects(oortObject1, oortObject2);

        final ReentrantLock lock = new ReentrantLock();
        final CountDownLatch updatedLatch = new CountDownLatch(1);
        oortObject1.addListener(new OortObject.Listener.Adapter<String>() {
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

        Assert.assertTrue(updatedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertNotNull(result.get(5, TimeUnit.SECONDS));
    }
}
