package org.cometd.client;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @version $Revision$ $Date$
 */
public class StatisticsHelper implements Runnable
{
    private final com.sun.management.OperatingSystemMXBean operatingSystem;
    private final CompilationMXBean jitCompiler;
    private final MemoryMXBean heapMemory;
    private final AtomicInteger starts = new AtomicInteger();
    private volatile MemoryPoolMXBean youngMemoryPool;
    private volatile MemoryPoolMXBean survivorMemoryPool;
    private volatile MemoryPoolMXBean oldMemoryPool;
    private volatile ScheduledFuture<?> memoryPoller;
    private volatile GarbageCollectorMXBean youngCollector;
    private volatile GarbageCollectorMXBean oldCollector;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean polling;
    private volatile long lastYoungUsed;
    private volatile long startYoungCollections;
    private volatile long startYoungCollectionsTime;
    private volatile long totalYoungUsed;
    private volatile long lastSurvivorUsed;
    private volatile long totalSurvivorUsed;
    private volatile long lastOldUsed;
    private volatile long startOldCollections;
    private volatile long startOldCollectionsTime;
    private volatile long totalOldUsed;
    private volatile long startTime;
    private volatile long startProcessCPUTime;
    private volatile long startJITCTime;

    public StatisticsHelper()
    {
        this.operatingSystem = (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        this.jitCompiler = ManagementFactory.getCompilationMXBean();
        this.heapMemory = ManagementFactory.getMemoryMXBean();
        List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean memoryPool : memoryPools)
        {
            if ("PS Eden Space".equals(memoryPool.getName()))
                youngMemoryPool = memoryPool;
            else if ("PS Survivor Space".equals(memoryPool.getName()))
                survivorMemoryPool = memoryPool;
            else if ("PS Old Gen".equals(memoryPool.getName()))
                oldMemoryPool = memoryPool;
        }
        List<GarbageCollectorMXBean> garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean garbageCollector : garbageCollectors)
        {
            if ("PS Scavenge".equals(garbageCollector.getName()))
                youngCollector = garbageCollector;
            else if ("PS MarkSweep".equals(garbageCollector.getName()))
                oldCollector = garbageCollector;
        }
    }

    public void run()
    {
        long young = youngMemoryPool.getUsage().getUsed();
        long survivor = survivorMemoryPool.getUsage().getUsed();
        long old = oldMemoryPool.getUsage().getUsed();

        if (!polling)
        {
            polling = true;
        }
        else
        {
            if (lastYoungUsed <= young)
            {
                totalYoungUsed += young - lastYoungUsed;
            }

            if (lastSurvivorUsed <= survivor)
            {
                totalSurvivorUsed += survivor - lastSurvivorUsed;
            }

            if (lastOldUsed <= old)
            {
                totalOldUsed += old - lastOldUsed;
            }
            else
            {
                // May need something more here, like "how much was collected"
            }
        }
        lastYoungUsed = young;
        lastSurvivorUsed = survivor;
        lastOldUsed = old;
    }

    public boolean startStatistics()
    {
        // Support for multiple nodes
        if (starts.incrementAndGet() > 1)
            return false;

        System.gc();
        System.err.println("\n========================================");           
        System.err.println("Statistics Started at " + new Date());
        System.err.println("Operative System: " + operatingSystem.getName() + " " + operatingSystem.getVersion() + " " + operatingSystem.getArch());
        System.err.println("JVM : "+System.getProperty("java.vm.vendor")+" "+System.getProperty("java.vm.name")+" runtime "+System.getProperty("java.vm.version")+" "+System.getProperty("java.runtime.version"));
        System.err.println("Processors: " + operatingSystem.getAvailableProcessors());
        long totalMemory = operatingSystem.getTotalPhysicalMemorySize();
        long freeMemory = operatingSystem.getFreePhysicalMemorySize();
        System.err.println("System Memory: " + percent(totalMemory - freeMemory, totalMemory) + "% used of " + gibiBytes(totalMemory) + " GiB");

        MemoryUsage heapMemoryUsage = heapMemory.getHeapMemoryUsage();
        System.err.println("Used Heap Size: " + mebiBytes(heapMemoryUsage.getUsed()) + " MiB");
        System.err.println("Max Heap Size: " + mebiBytes(heapMemoryUsage.getMax()) + " MiB");
        long youngGenerationHeap = heapMemoryUsage.getMax() - oldMemoryPool.getUsage().getMax();
        System.err.println("Young Generation Heap Size: " + mebiBytes(youngGenerationHeap) + " MiB");
        System.err.println("- - - - - - - - - - - - - - - - - - - - ");   
        
        scheduler = Executors.newSingleThreadScheduledExecutor();
        polling = false;
        memoryPoller = scheduler.scheduleWithFixedDelay(this, 0, 500, TimeUnit.MILLISECONDS);

        lastYoungUsed = 0;
        startYoungCollections = youngCollector.getCollectionCount();
        startYoungCollectionsTime = youngCollector.getCollectionTime();
        totalYoungUsed = 0;
        lastSurvivorUsed = 0;
        totalSurvivorUsed = 0;
        lastOldUsed = 0;
        startOldCollections = oldCollector.getCollectionCount();
        startOldCollectionsTime = oldCollector.getCollectionTime();
        totalOldUsed = 0;

        startTime = System.nanoTime();
        startProcessCPUTime = operatingSystem.getProcessCpuTime();
        startJITCTime = jitCompiler.getTotalCompilationTime();

        return true;
    }

    public boolean stopStatistics()
    {
        // Support for multiple nodes
        if (starts.decrementAndGet() > 0)
            return false;

        long elapsedJITCTime = jitCompiler.getTotalCompilationTime() - startJITCTime;
        long elapsedProcessCPUTime = operatingSystem.getProcessCpuTime() - startProcessCPUTime;
        long elapsedTime = System.nanoTime() - startTime;

        long elapsedOldCollectionsTime = oldCollector.getCollectionTime() - startOldCollectionsTime;
        long oldCollections = oldCollector.getCollectionCount() - startOldCollections;
        long elapsedYoungCollectionsTime = youngCollector.getCollectionTime() - startYoungCollectionsTime;
        long youngCollections = youngCollector.getCollectionCount() - startYoungCollections;

        memoryPoller.cancel(false);
        scheduler.shutdown();

        System.err.println("- - - - - - - - - - - - - - - - - - - - ");
        System.err.println("Statistics Ended at " + new Date());
        System.err.println("Elapsed time: " + TimeUnit.NANOSECONDS.toMillis(elapsedTime) + " ms");
        System.err.println("\tTime in JIT compilation: " + elapsedJITCTime + " ms");
        System.err.println("\tTime in Young Generation GC: " + elapsedYoungCollectionsTime + " ms (" + youngCollections + " collections)");
        System.err.println("\tTime in Old Generation GC: " + elapsedOldCollectionsTime + " ms (" + oldCollections + " collections)");

        System.err.println("Garbage Generated in Young Generation: " + mebiBytes(totalYoungUsed) + " MiB");
        System.err.println("Garbage Generated in Survivor Generation: " + mebiBytes(totalSurvivorUsed) + " MiB");
        System.err.println("Garbage Generated in Old Generation: " + mebiBytes(totalOldUsed) + " MiB");

        System.err.println("Average CPU Load: " + ((float)elapsedProcessCPUTime * 100 / elapsedTime) + "/" + (100 * operatingSystem.getAvailableProcessors()));
        System.err.println("----------------------------------------\n");  
        return true;
    }

    public float percent(long dividend, long divisor)
    {
        return (float)dividend * 100 / divisor;
    }

    public float mebiBytes(long bytes)
    {
        return (float)bytes / 1024 / 1024;
    }

    public float gibiBytes(long bytes)
    {
        return (float)bytes / 1024 / 1024 / 1024;
    }
}
