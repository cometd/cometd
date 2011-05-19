package org.cometd.client.benchmark;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Atomics
{
    public static void updateMin(AtomicLong currentMin, long newValue)
    {
        long oldValue = currentMin.get();
        while (newValue < oldValue)
        {
            if (currentMin.compareAndSet(oldValue, newValue))
                break;
            oldValue = currentMin.get();
        }
    }

    public static void updateMax(AtomicLong currentMax, long newValue)
    {
        long oldValue = currentMax.get();
        while (newValue > oldValue)
        {
            if (currentMax.compareAndSet(oldValue, newValue))
                break;
            oldValue = currentMax.get();
        }
    }

    public static void updateMin(AtomicInteger currentMin, int newValue)
    {
        int oldValue = currentMin.get();
        while (newValue < oldValue)
        {
            if (currentMin.compareAndSet(oldValue, newValue))
                break;
            oldValue = currentMin.get();
        }
    }

    public static void updateMax(AtomicInteger currentMax, int newValue)
    {
        int oldValue = currentMax.get();
        while (newValue > oldValue)
        {
            if (currentMax.compareAndSet(oldValue, newValue))
                break;
            oldValue = currentMax.get();
        }
    }
}
