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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

public class OortMasterLongTest extends AbstractOortObjectTest {
    public OortMasterLongTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testCount() throws Exception {
        String name = "test";
        final long initial = 3;
        OortMasterLong counter1 = new OortMasterLong(oort1, name, true, initial);
        OortMasterLong counter2 = new OortMasterLong(oort2, name, false);
        counter1.start();
        // Wait for counter1 to be started
        Thread.sleep(1000);
        counter2.start();
        // Wait for the nodes to synchronize
        Thread.sleep(1000);

        final CountDownLatch latch1 = new CountDownLatch(1);
        Assert.assertTrue(counter1.get(new OortMasterLong.Callback.Adapter() {
            @Override
            public void succeeded(Long result) {
                Assert.assertEquals(initial, (long)result);
                latch1.countDown();
            }
        }));
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        // Make sure the local value is set
        Assert.assertEquals(initial, counter1.getValue());

        final CountDownLatch latch2 = new CountDownLatch(1);
        Assert.assertTrue(counter2.get(new OortMasterLong.Callback.Adapter() {
            @Override
            public void succeeded(Long result) {
                Assert.assertEquals(initial, (long)result);
                latch2.countDown();
            }
        }));
        Assert.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        // Make sure the local value is not set
        Assert.assertEquals(0, counter2.getValue());

        final CountDownLatch latch3 = new CountDownLatch(1);
        Assert.assertTrue(counter1.addAndGet(1, new OortMasterLong.Callback.Adapter() {
            @Override
            public void succeeded(Long result) {
                Assert.assertEquals(initial + 1, (long)result);
                latch3.countDown();
            }
        }));
        Assert.assertTrue(latch3.await(5, TimeUnit.SECONDS));
        // Make sure the local value is set
        Assert.assertEquals(initial + 1, counter1.getValue());

        final CountDownLatch latch4 = new CountDownLatch(1);
        Assert.assertTrue(counter2.addAndGet(1, new OortMasterLong.Callback.Adapter() {
            @Override
            public void succeeded(Long result) {
                Assert.assertEquals(initial + 2, (long)result);
                latch4.countDown();
            }
        }));
        Assert.assertTrue(latch4.await(5, TimeUnit.SECONDS));
        // Make sure the local value is not set
        Assert.assertEquals(0, counter2.getValue());

        final CountDownLatch latch5 = new CountDownLatch(1);
        Assert.assertTrue(counter2.getAndAdd(1, new OortMasterLong.Callback.Adapter() {
            @Override
            public void succeeded(Long result) {
                Assert.assertEquals(initial + 2, (long)result);
                latch5.countDown();
            }
        }));
        Assert.assertTrue(latch5.await(5, TimeUnit.SECONDS));
        // Make sure the local value is not set
        Assert.assertEquals(0, counter2.getValue());

        final CountDownLatch latch6 = new CountDownLatch(1);
        Assert.assertTrue(counter1.get(new OortMasterLong.Callback.Adapter() {
            @Override
            public void succeeded(Long result) {
                Assert.assertEquals(initial + 3, (long)result);
                latch6.countDown();
            }
        }));
        Assert.assertTrue(latch6.await(5, TimeUnit.SECONDS));
        // Make sure the local value is set
        Assert.assertEquals(initial + 3, counter1.getValue());
    }
}
