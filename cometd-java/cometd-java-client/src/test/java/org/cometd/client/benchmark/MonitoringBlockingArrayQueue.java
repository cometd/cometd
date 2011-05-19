package org.cometd.client.benchmark;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.BlockingArrayQueue;

public class MonitoringBlockingArrayQueue extends BlockingArrayQueue<Runnable>
{
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicInteger maxSize = new AtomicInteger();
    private final AtomicLong maxLatency = new AtomicLong();
    private final AtomicLong totLatency = new AtomicLong();

    public MonitoringBlockingArrayQueue(int capacity, int growBy)
    {
        super(capacity, growBy);
    }

    @Override
    public void clear()
    {
        reset();
        super.clear();
    }

    @Override
    public boolean offer(final Runnable job)
    {
        final long begin = System.nanoTime();
        boolean result = super.offer(new Runnable()
        {
            public void run()
            {
                count.incrementAndGet();
                long latency = System.nanoTime() - begin;
                Atomics.updateMax(maxLatency, latency);
                totLatency.addAndGet(latency);
                job.run();
            }
        });
        if (result)
            increment();
        return result;
    }

    private void increment()
    {
        int value = size.incrementAndGet();
        Atomics.updateMax(maxSize, value);
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

    public void reset()
    {
        count.set(0);
        size.set(0);
        maxSize.set(0);
        maxLatency.set(0);
        totLatency.set(0);
    }

    public int getMaxSize()
    {
        return maxSize.get();
    }

    public long getMaxLatency()
    {
        return maxLatency.get();
    }

    public long getAverageLatency()
    {
        int count = this.count.get();
        return count == 0 ? -1 : totLatency.get() / count;
    }
}
