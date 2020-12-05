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
package org.cometd.server.ext;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BatchArrayQueueTest {
    @Test
    public void test_Offer_Next_Offer_Export_Clear() {
        BatchArrayQueue<String> queue = new BatchArrayQueue<>(16, new ReentrantLock());

        queue.offer("A");
        long batch = queue.getBatch();
        queue.nextBatch();

        queue.offer("B");

        Queue<String> target = new ArrayDeque<>();
        queue.exportMessagesToBatch(target, batch);

        Assertions.assertEquals(1, target.size());
        String targetItem = target.peek();
        Assertions.assertNotNull(targetItem);
        Assertions.assertTrue(targetItem.startsWith("A"));

        queue.clearToBatch(batch);

        Assertions.assertEquals(1, queue.size());
        String queueItem = queue.peek();
        Assertions.assertNotNull(queueItem);
        Assertions.assertTrue(queueItem.startsWith("B"));
    }

    @Test
    public void test_Offer_Grow_Poll_Offer() {
        BatchArrayQueue<String> queue = new BatchArrayQueue<>(2, new ReentrantLock());

        queue.offer("A1");
        queue.offer("A2");
        queue.offer("A3");

        long batch = queue.getBatch();
        queue.nextBatch();
        long nextBatch = queue.getBatch();

        queue.offer("B1");

        Assertions.assertEquals(batch, queue.batchOf(0));
        Assertions.assertEquals(batch, queue.batchOf(1));
        Assertions.assertEquals(batch, queue.batchOf(2));
        Assertions.assertEquals(nextBatch, queue.batchOf(3));

        queue.poll();
        queue.offer("B2");

        Assertions.assertEquals(batch, queue.batchOf(0));
        Assertions.assertEquals(batch, queue.batchOf(1));
        Assertions.assertEquals(nextBatch, queue.batchOf(2));
        Assertions.assertEquals(nextBatch, queue.batchOf(3));
    }

    @Test
    public void test_Offer_Grow_Next_Offer_Grow_Export_Clear() {
        BatchArrayQueue<String> queue = new BatchArrayQueue<>(2, new ReentrantLock());

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

        Assertions.assertEquals(3, target.size());
        for (String element : target) {
            Assertions.assertTrue(element.startsWith("A"));
        }

        queue.clearToBatch(batch);

        for (String element : queue) {
            Assertions.assertTrue(element.startsWith("B"));
        }
    }
}
