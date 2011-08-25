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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MonitoringThreadPoolExecutor extends ThreadPoolExecutor
{
    private final ConcurrentMap<Runnable, Long> tasks = new ConcurrentHashMap<Runnable, Long>();
    private final AtomicLong taskCount = new AtomicLong();
    private final AtomicLong maxLatency = new AtomicLong();
    private final AtomicLong totalLatency = new AtomicLong();
    private final AtomicInteger threads = new AtomicInteger();
    private final AtomicInteger maxThreads = new AtomicInteger();

    public MonitoringThreadPoolExecutor(int maximumPoolSize, long keepAliveTime, TimeUnit unit)
    {
        this(maximumPoolSize, keepAliveTime, unit, new AbortPolicy());
    }

    public MonitoringThreadPoolExecutor(int maximumPoolSize, long keepAliveTime, TimeUnit unit, RejectedExecutionHandler handler)
    {
        super(maximumPoolSize, maximumPoolSize, keepAliveTime, unit, new MonitoringLinkedBlockingQueue(), handler);
    }

    public void reset()
    {
        ((MonitoringLinkedBlockingQueue)getQueue()).reset();
        tasks.clear();
        taskCount.set(0);
        maxLatency.set(0);
        totalLatency.set(0);
        threads.set(0);
        maxThreads.set(0);
    }

    public long getMaxQueueLatency()
    {
        return maxLatency.get();
    }

    public long getAverageQueueLatency()
    {
        long count = taskCount.get();
        return count == 0 ? -1 : totalLatency.get() / count;
    }

    public int getMaxQueueSize()
    {
        return ((MonitoringLinkedBlockingQueue)getQueue()).maxSize.get();
    }

    public int getMaxThreads()
    {
        return maxThreads.get();
    }

    @Override
    public void execute(final Runnable task)
    {
        Runnable runnable = task;
        long nanos = System.nanoTime();
        Long existing = tasks.putIfAbsent(runnable, nanos);
        if (existing != null)
        {
            // Need to wrap in order to guarantee
            // that we're not passed the same task
            runnable = new Runnable()
            {
                public void run()
                {
                    task.run();
                }
            };
            tasks.put(runnable, nanos);
        }
        super.execute(runnable);
    }

    @Override
    protected void beforeExecute(Thread thread, Runnable task)
    {
        long time = System.nanoTime() - tasks.remove(task);
        taskCount.incrementAndGet();
        Atomics.updateMax(maxLatency, time);
        totalLatency.addAndGet(time);
        Atomics.updateMax(maxThreads, threads.incrementAndGet());
        super.beforeExecute(thread, task);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t)
    {
        threads.decrementAndGet();
        super.afterExecute(r, t);
    }

    private static class MonitoringLinkedBlockingQueue extends LinkedBlockingQueue<Runnable>
    {
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicInteger maxSize = new AtomicInteger();

        public void reset()
        {
            size.set(0);
            maxSize.set(0);
        }

        @Override
        public boolean offer(Runnable task)
        {
            boolean added = super.offer(task);
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
            Runnable task = super.poll();
            if (task != null)
                decrement();
            return task;
        }

        @Override
        public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException
        {
            Runnable task = super.poll(timeout, unit);
            if (task != null)
                decrement();
            return task;
        }

        @Override
        public Runnable take() throws InterruptedException
        {
            Runnable task = super.take();
            decrement();
            return task;
        }

        private void decrement()
        {
            size.decrementAndGet();
        }
    }
}
