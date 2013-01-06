/*
 * Copyright (c) 2010 the original author or authors.
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

/**
 * <p>Detects and reports the timer resolution of the current running platform.</p>
 * <p>Unfortunately, {@link Thread#sleep(long)} on many platforms has a resolution of 1 ms
 * or even of 10 ms, so calling {@code Thread.sleep(2)} often results in a 10 ms sleep.<br/>
 * The same applies for {@link Thread#sleep(long, int)} and {@link Object#wait(long, int)}:
 * they are not accurate, especially on virtualized platforms (like Amazon EC2, where the
 * resolution can be as high as 64 ms).</p>
 * <p>{@link System#nanoTime()} is precise enough, but we would need to loop continuously
 * checking the nano time until the sleep period is elapsed; to avoid busy looping pegging
 * the CPUs, {@link Thread#yield()} is called to attempt to reduce the CPU load.</p>
 */
public class SystemTimer
{
    private final long nativeResolution;
    private final long emulatedResolution;

    private SystemTimer(long nativeResolution, long emulatedResolution)
    {
        this.nativeResolution = nativeResolution;
        this.emulatedResolution = emulatedResolution;
    }

    public long getNativeResolution()
    {
        return nativeResolution;
    }

    public long getEmulatedResolution()
    {
        return emulatedResolution;
    }

    public void sleep(long micros)
    {
        if (micros > nativeResolution)
            sleepNative(micros);
        else
            sleepEmulated(micros);
    }

    @Override
    public String toString()
    {
        return getClass().getName() + "[native=" + getNativeResolution() + ",emulated=" + getEmulatedResolution() + "]";
    }

    public static SystemTimer detect()
    {
        detectNative();
        long nativeAccuracy = detectNative();
        detectEmulated();
        long emulatedAccuracy = detectEmulated();
        while (emulatedAccuracy > nativeAccuracy)
            emulatedAccuracy = detectEmulated();
        return new SystemTimer(nativeAccuracy, emulatedAccuracy);
    }

    private static long detectNative()
    {
        return detect(true);
    }

    private static long detectEmulated()
    {
        return detect(false);
    }

    private static long detect(boolean useNative)
    {
        // Avoid stop-the-world pauses from the GC
        System.gc();

        long min = 0;
        long max = 100000;
        long value = max;

        while (max > min + 1)
        {
            long begin = System.nanoTime();
            if (useNative)
                sleepNative(value);
            else
                sleepEmulated(value);
            long end = System.nanoTime();

            long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(end - begin);
            if (elapsedMicros > value + (value / 10))
                min = value;
            else
                max = value;
            value = (min + max) / 2;
        }
        return value;
    }

    private static void sleepNative(long micros)
    {
        try
        {
            TimeUnit.MICROSECONDS.sleep(micros);
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            throw new RuntimeException(x);
        }
    }

    private static void sleepEmulated(long micros)
    {
        long end = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(micros);
        while (System.nanoTime() < end)
            Thread.yield();
    }

    public static void main(String[] args)
    {
        final SystemTimer systemTimer = SystemTimer.detect();
        System.out.println("systemTimer = " + systemTimer);
    }
}
