/*
 * Copyright (c) 2008-2021 the original author or authors.
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
package org.cometd.benchmark;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class MonitoringQueuedThreadPool extends QueuedThreadPool {
    private final AtomicLong tasks = new AtomicLong();
    private final AtomicLong maxTaskLatency = new AtomicLong();
    private final AtomicLong totalTaskLatency = new AtomicLong();
    private final MonitoringBlockingArrayQueue queue;
    private final AtomicLong maxQueueLatency = new AtomicLong();
    private final AtomicLong totalQueueLatency = new AtomicLong();
    private final AtomicInteger threads = new AtomicInteger();
    private final AtomicInteger maxThreads = new AtomicInteger();

    public MonitoringQueuedThreadPool(int maxThreads) {
        // Use a very long idle timeout to avoid creation/destruction of threads
        super(maxThreads, maxThreads, 24 * 3600 * 1000, new MonitoringBlockingArrayQueue(maxThreads, 256));
        queue = (MonitoringBlockingArrayQueue)getQueue();
        setStopTimeout(2000);
    }

    @Override
    public void execute(final Runnable job) {
        final long begin = System.nanoTime();
        super.execute(new Runnable() {
            @Override
            public void run() {
                long queueLatency = System.nanoTime() - begin;
                tasks.incrementAndGet();
                Atomics.updateMax(maxQueueLatency, queueLatency);
                totalQueueLatency.addAndGet(queueLatency);
                Atomics.updateMax(maxThreads, threads.incrementAndGet());
                long start = System.nanoTime();
                try {
                    job.run();
                } finally {
                    long taskLatency = System.nanoTime() - start;
                    threads.decrementAndGet();
                    Atomics.updateMax(maxTaskLatency, taskLatency);
                    totalTaskLatency.addAndGet(taskLatency);
                }
            }

            @Override
            public String toString () {
                return job.toString();
            }
        });
    }

    public void reset() {
        tasks.set(0);
        maxTaskLatency.set(0);
        totalTaskLatency.set(0);
        queue.reset();
        maxQueueLatency.set(0);
        totalQueueLatency.set(0);
        threads.set(0);
        maxThreads.set(0);
    }

    public long getTasks() {
        return tasks.get();
    }

    public int getMaxActiveThreads() {
        return maxThreads.get();
    }

    public int getMaxQueueSize() {
        return queue.maxSize.get();
    }

    public long getAverageQueueLatency() {
        long count = tasks.get();
        return count == 0 ? -1 : totalQueueLatency.get() / count;
    }

    public long getMaxQueueLatency() {
        return maxQueueLatency.get();
    }

    public long getMaxTaskLatency() {
        return maxTaskLatency.get();
    }

    public long getAverageTaskLatency() {
        long count = tasks.get();
        return count == 0 ? -1 : totalTaskLatency.get() / count;
    }

    public static class MonitoringBlockingArrayQueue extends BlockingArrayQueue<Runnable> {
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicInteger maxSize = new AtomicInteger();

        public MonitoringBlockingArrayQueue(int capacity, int growBy) {
            super(capacity, growBy);
        }

        public void reset() {
            size.set(0);
            maxSize.set(0);
        }

        @Override
        public void clear() {
            reset();
            super.clear();
        }

        @Override
        public boolean offer(Runnable job) {
            boolean added = super.offer(job);
            if (added) {
                increment();
            }
            return added;
        }

        private void increment() {
            Atomics.updateMax(maxSize, size.incrementAndGet());
        }

        @Override
        public Runnable poll() {
            Runnable job = super.poll();
            if (job != null) {
                decrement();
            }
            return job;
        }

        @Override
        public Runnable poll(long time, TimeUnit unit) throws InterruptedException {
            Runnable job = super.poll(time, unit);
            if (job != null) {
                decrement();
            }
            return job;
        }

        @Override
        public Runnable take() throws InterruptedException {
            Runnable job = super.take();
            decrement();
            return job;
        }

        private void decrement() {
            size.decrementAndGet();
        }
    }
}
