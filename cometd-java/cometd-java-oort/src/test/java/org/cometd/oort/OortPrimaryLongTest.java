/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class OortPrimaryLongTest extends AbstractOortObjectTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testCount(String serverTransport) throws Exception {
        prepare(serverTransport);

        String name = "test";
        long initial = 3;
        OortPrimaryLong counter1 = new OortPrimaryLong(oort1, name, true, initial);
        OortPrimaryLong counter2 = new OortPrimaryLong(oort2, name, false);
        counter1.start();
        // Wait for counter1 to be started
        Thread.sleep(1000);
        counter2.start();
        // Wait for the nodes to synchronize
        Thread.sleep(1000);

        CountDownLatch latch1 = new CountDownLatch(1);
        Assertions.assertTrue(counter1.get(new OortPrimaryLong.Callback() {
            @Override
            public void succeeded(Long result) {
                Assertions.assertEquals(initial, (long)result);
                latch1.countDown();
            }
        }));
        Assertions.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        // Make sure the local value is set
        Assertions.assertEquals(initial, counter1.getValue());

        CountDownLatch latch2 = new CountDownLatch(1);
        Assertions.assertTrue(counter2.get(new OortPrimaryLong.Callback() {
            @Override
            public void succeeded(Long result) {
                Assertions.assertEquals(initial, (long)result);
                latch2.countDown();
            }
        }));
        Assertions.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        // Make sure the local value is not set
        Assertions.assertEquals(0, counter2.getValue());

        CountDownLatch latch3 = new CountDownLatch(1);
        Assertions.assertTrue(counter1.addAndGet(1, new OortPrimaryLong.Callback() {
            @Override
            public void succeeded(Long result) {
                Assertions.assertEquals(initial + 1, (long)result);
                latch3.countDown();
            }
        }));
        Assertions.assertTrue(latch3.await(5, TimeUnit.SECONDS));
        // Make sure the local value is set
        Assertions.assertEquals(initial + 1, counter1.getValue());

        CountDownLatch latch4 = new CountDownLatch(1);
        Assertions.assertTrue(counter2.addAndGet(1, new OortPrimaryLong.Callback() {
            @Override
            public void succeeded(Long result) {
                Assertions.assertEquals(initial + 2, (long)result);
                latch4.countDown();
            }
        }));
        Assertions.assertTrue(latch4.await(5, TimeUnit.SECONDS));
        // Make sure the local value is not set
        Assertions.assertEquals(0, counter2.getValue());

        CountDownLatch latch5 = new CountDownLatch(1);
        Assertions.assertTrue(counter2.getAndAdd(1, new OortPrimaryLong.Callback() {
            @Override
            public void succeeded(Long result) {
                Assertions.assertEquals(initial + 2, (long)result);
                latch5.countDown();
            }
        }));
        Assertions.assertTrue(latch5.await(5, TimeUnit.SECONDS));
        // Make sure the local value is not set
        Assertions.assertEquals(0, counter2.getValue());

        CountDownLatch latch6 = new CountDownLatch(1);
        Assertions.assertTrue(counter1.get(new OortPrimaryLong.Callback() {
            @Override
            public void succeeded(Long result) {
                Assertions.assertEquals(initial + 3, (long)result);
                latch6.countDown();
            }
        }));
        Assertions.assertTrue(latch6.await(5, TimeUnit.SECONDS));
        // Make sure the local value is set
        Assertions.assertEquals(initial + 3, counter1.getValue());
    }
}
