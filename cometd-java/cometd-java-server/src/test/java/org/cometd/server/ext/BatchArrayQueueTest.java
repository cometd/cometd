/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.server.ext;

import java.util.ArrayDeque;
import java.util.Queue;

import org.junit.Assert;
import org.junit.Test;

public class BatchArrayQueueTest {
    @Test
    public void test_Offer_Next_Offer_Export_Clear() throws Exception {
        BatchArrayQueue<String> queue = new BatchArrayQueue<>(16, this);

        queue.offer("A");
        long batch = queue.getBatch();
        queue.nextBatch();

        queue.offer("B");

        Queue<String> target = new ArrayDeque<>();
        queue.exportMessagesToBatch(target, batch);

        Assert.assertEquals(1, target.size());
        Assert.assertTrue(target.peek().startsWith("A"));

        queue.clearToBatch(batch);

        Assert.assertEquals(1, queue.size());
        Assert.assertTrue(queue.peek().startsWith("B"));
    }

    @Test
    public void test_Offer_Grow_Poll_Offer() throws Exception {
        BatchArrayQueue<String> queue = new BatchArrayQueue<>(2, this);

        queue.offer("A1");
        queue.offer("A2");
        queue.offer("A3");

        long batch = queue.getBatch();
        queue.nextBatch();
        long nextBatch = queue.getBatch();

        queue.offer("B1");

        Assert.assertEquals(batch, queue.batchOf(0));
        Assert.assertEquals(batch, queue.batchOf(1));
        Assert.assertEquals(batch, queue.batchOf(2));
        Assert.assertEquals(nextBatch, queue.batchOf(3));

        queue.poll();
        queue.offer("B2");

        Assert.assertEquals(batch, queue.batchOf(0));
        Assert.assertEquals(batch, queue.batchOf(1));
        Assert.assertEquals(nextBatch, queue.batchOf(2));
        Assert.assertEquals(nextBatch, queue.batchOf(3));
    }

    @Test
    public void test_Offer_Grow_Next_Offer_Grow_Export_Clear() throws Exception {
        BatchArrayQueue<String> queue = new BatchArrayQueue<>(2, this);

        queue.offer("A1");
        queue.offer("A2");
        queue.offer("A3");
        long batch = queue.getBatch();
        queue.nextBatch();

        queue.offer("B1");
        queue.offer("B2");
        queue.offer("B3");

        Queue<String> target = new ArrayDeque<>();
        queue.exportMessagesToBatch(target, batch);

        Assert.assertEquals(3, target.size());
        for (String element : target) {
            Assert.assertTrue(element.startsWith("A"));
        }

        queue.clearToBatch(batch);

        for (String element : queue) {
            Assert.assertTrue(element.startsWith("B"));
        }
    }
}
