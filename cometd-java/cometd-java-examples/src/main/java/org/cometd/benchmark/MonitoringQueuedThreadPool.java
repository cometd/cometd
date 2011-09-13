/*
 * Copyright (c) 2011 the original author or authors.
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

public class MonitoringQueuedThreadPool extends QueuedThreadPool
{
    private final AtomicInteger jobs = new AtomicInteger();
    private final AtomicLong maxLatency = new AtomicLong();
    private final AtomicLong totalLatency = new AtomicLong();
    private final AtomicInteger threads = new AtomicInteger();
    private final AtomicInteger maxThreads = new AtomicInteger();
    private final MonitoringBlockingArrayQueue queue;

    public MonitoringQueuedThreadPool(int maxThreads)
    {
        super(new MonitoringBlockingArrayQueue(maxThreads, maxThreads));
        setMaxThreads(maxThreads);
        queue = (MonitoringBlockingArrayQueue)getQueue();
        setMaxStopTimeMs(2000);
    }

    @Override
    public boolean dispatch(final Runnable job)
    {
        final long begin = System.nanoTime();
        return super.dispatch(new Runnable()
        {
            public void run()
            {
                long latency = System.nanoTime() - begin;
                Atomics.updateMax(maxLatency, latency);
                totalLatency.addAndGet(latency);
                jobs.incrementAndGet();
                Atomics.updateMax(maxThreads, threads.incrementAndGet());
                try
                {
                    job.run();
                }
                finally
                {
                    threads.decrementAndGet();
                }
            }
        });
    }

    public void reset()
    {
        queue.reset();
        jobs.set(0);
        maxLatency.set(0);
        totalLatency.set(0);
        threads.set(0);
        maxThreads.set(0);
    }

    public int getMaxActiveThreads()
    {
        return maxThreads.get();
    }

    public int getMaxQueueSize()
    {
        return queue.maxSize.get();
    }

    public long getAverageQueueLatency()
    {
        int count = jobs.get();
        return count == 0 ? -1 : totalLatency.get() / count;
    }

    public long getMaxQueueLatency()
    {
        return maxLatency.get();
    }

    public static class MonitoringBlockingArrayQueue extends BlockingArrayQueue<Runnable>
    {
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicInteger maxSize = new AtomicInteger();

        public MonitoringBlockingArrayQueue(int capacity, int growBy)
        {
            super(capacity, growBy);
        }

        public void reset()
        {
            size.set(0);
            maxSize.set(0);
        }

        @Override
        public void clear()
        {
            reset();
            super.clear();
        }

        @Override
        public boolean offer(Runnable job)
        {
            boolean added = super.offer(job);
            if (added)
                increment();
            return added;
        }

        private void increment()
        {
            Atomics.updateMax(maxSize, size.incrementAndGet());
        }

        @Override
        public Runnable poll()
        {
            Runnable job = super.poll();
            if (job != null)
                decrement();
            return job;
        }

        @Override
        public Runnable poll(long time, TimeUnit unit) throws InterruptedException
        {
            Runnable job = super.poll(time, unit);
            if (job != null)
                decrement();
            return job;
        }

        @Override
        public Runnable take() throws InterruptedException
        {
            Runnable job = super.take();
            decrement();
            return job;
        }

        private void decrement()
        {
            size.decrementAndGet();
        }
    }
}
