/*
 * Copyright (c) 2008 the original author or authors.
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BatchArrayQueueTest {
    @Test
    public void testOfferNextOfferExportClear() {
        BatchArrayQueue<String> queue = new BatchArrayQueue<>(16, new ReentrantLock());

        queue.offer("A");
        long batch = queue.getBatch();
        queue.nextBatch();

        queue.offer("B");

        Queue<String> target = new ArrayDeque<>();
        queue.exportMessagesToBatch(target, batch);

        assertEquals(1, target.size());
        String targetItem = target.peek();
        assertNotNull(targetItem);
        assertTrue(targetItem.startsWith("A"));

        queue.clearToBatch(batch);

        assertEquals(1, queue.size());
        String queueItem = queue.peek();
        assertNotNull(queueItem);
        assertTrue(queueItem.startsWith("B"));
    }

    @Test
    public void testOfferGrowPollOffer() {
        BatchArrayQueue<String> queue = new BatchArrayQueue<>(2, new ReentrantLock());

        queue.offer("A1");
        queue.offer("A2");
        queue.offer("A3");

        long batch = queue.getBatch();
        queue.nextBatch();
        long nextBatch = queue.getBatch();

        queue.offer("B1");

        assertEquals(batch, queue.batchOf(0));
        assertEquals(batch, queue.batchOf(1));
        assertEquals(batch, queue.batchOf(2));
        assertEquals(nextBatch, queue.batchOf(3));

        queue.poll();
        queue.offer("B2");

        assertEquals(batch, queue.batchOf(0));
        assertEquals(batch, queue.batchOf(1));
        assertEquals(nextBatch, queue.batchOf(2));
        assertEquals(nextBatch, queue.batchOf(3));
    }

    @Test
    public void testOfferGrowNextOfferGrowExportClear() {
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

        assertEquals(3, target.size());
        for (String element : target) {
            assertTrue(element.startsWith("A"));
        }

        queue.clearToBatch(batch);

        for (String element : queue) {
            assertTrue(element.startsWith("B"));
        }
    }

    @Test
    public void testOfferNextOfferClearToCurrent() {
        BatchArrayQueue<String> queue = new BatchArrayQueue<>(16, new ReentrantLock());

        queue.offer("A");
        queue.nextBatch();
        queue.offer("B");
        long batch = queue.getBatch();
        queue.clearToBatch(batch);
        assertEquals(0, queue.size());
        assertEquals(batch, queue.getBatch());

        queue.nextBatch();
        queue.offer("C");
        queue.clear();
        assertEquals(0, queue.size());
        assertEquals(1, queue.getBatch());
    }
}
