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

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxService;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * @version $Revision: 781 $ $Date: 2009-10-08 19:34:08 +1100 (Thu, 08 Oct 2009) $
 */
public class BayeuxDemoServer
{
    public static void main(String[] args) throws Exception
    {
        int port = 8080;
        if (args.length > 0)
            port = Integer.parseInt(args[0]);

        Server server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        // Make sure the OS is configured properly for load testing;
        // see http://docs.codehaus.org/display/JETTY/HighLoadServers
        connector.setAcceptQueueSize(2048);
        // Make sure the server timeout on a TCP connection is large
        connector.setMaxIdleTime(240000);
        connector.setPort(port);
        server.addConnector(connector);

        QueuedThreadPool threadPool = new QueuedThreadPool();
        server.setThreadPool(threadPool);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        // Setup default servlet to serve static files
        context.addServlet(DefaultServlet.class, "/");

        // Setup comet servlet
        String cometServletPath = "/cometd";
        CometdServlet cometServlet = new CometdServlet();
        ServletHolder cometServletHolder = new ServletHolder(cometServlet);
        // Make sure the expiration timeout is large to avoid clients to timeout
        // This value must be several times larger than the client value
        // (e.g. 60 s on server vs 5 s on client) so that it's guaranteed that
        // it will be the client to dispose idle connections.
        cometServletHolder.setInitParameter("maxInterval", String.valueOf(60000));
        // Explicitly set the timeout value
        cometServletHolder.setInitParameter("timeout", String.valueOf(30000));
        context.addServlet(cometServletHolder, cometServletPath + "/*");

        server.start();

        BayeuxServer bayeux = cometServlet.getBayeux();
        new StatisticsService(bayeux);
    }

    public static class StatisticsService extends BayeuxService implements Runnable
    {
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final com.sun.management.OperatingSystemMXBean operatingSystem;
        private final CompilationMXBean jitCompiler;
        private final MemoryMXBean heapMemory;
        private volatile MemoryPoolMXBean youngMemoryPool;
        private volatile MemoryPoolMXBean survivorMemoryPool;
        private volatile MemoryPoolMXBean oldMemoryPool;
        private volatile ScheduledFuture<?> memoryPoller;
        private volatile GarbageCollectorMXBean youngCollector;
        private volatile GarbageCollectorMXBean oldCollector;
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

        private StatisticsService(BayeuxServer bayeux)
        {
            super(bayeux, "statistics-service");
            subscribe("/service/statistics/start", "startStatistics");
            subscribe("/service/statistics/stop", "stopStatistics");
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

        public void startStatistics(ServerSession remote, Message message)
        {
            System.gc();

            System.err.println("----------------------------------------");
            System.err.println("Server Statistics Started at " + new Date());
            System.err.println("Operative System: " + operatingSystem.getName() + " " + operatingSystem.getVersion() + " " + operatingSystem.getArch());
            System.err.println("Processors: " + operatingSystem.getAvailableProcessors());
            long totalMemory = operatingSystem.getTotalPhysicalMemorySize();
            long freeMemory = operatingSystem.getFreePhysicalMemorySize();
            System.err.println("System Memory: " + percent(totalMemory - freeMemory, totalMemory) + "% used of " + gibiBytes(totalMemory) + " GiB");

            MemoryUsage heapMemoryUsage = heapMemory.getHeapMemoryUsage();
            System.err.println("Used Heap Size: " + mebiBytes(heapMemoryUsage.getUsed()) + " MiB");
            System.err.println("Max Heap Size: " + mebiBytes(heapMemoryUsage.getMax()) + " MiB");
            long youngGenerationHeap = heapMemoryUsage.getMax() - oldMemoryPool.getUsage().getMax();
            System.err.println("Young Generation Heap Size: " + mebiBytes(youngGenerationHeap) + " MiB");

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

//            MessageImpl.__instances.set(0);
        }

        public void stopStatistics(ServerSession remote, Message message) throws Exception
        {
//            long instances = MessageImpl.__instances.get();

            long elapsedJITCTime = jitCompiler.getTotalCompilationTime() - startJITCTime;
            long elapsedProcessCPUTime = operatingSystem.getProcessCpuTime() - startProcessCPUTime;
            long elapsedTime = System.nanoTime() - startTime;

            long elapsedOldCollectionsTime = oldCollector.getCollectionTime() - startOldCollectionsTime;
            long oldCollections = oldCollector.getCollectionCount() - startOldCollections;
            long elapsedYoungCollectionsTime = youngCollector.getCollectionTime() - startYoungCollectionsTime;
            long youngCollections = youngCollector.getCollectionCount() - startYoungCollections;

            memoryPoller.cancel(false);

            System.err.println("- - - - - - - - - - - - - - - - - - - - ");
            System.err.println("Server Statistics Ended at " + new Date());
            System.err.println("Elapsed time: " + TimeUnit.NANOSECONDS.toMillis(elapsedTime) + " ms");
            System.err.println("\tTime in JIT compilation: " + elapsedJITCTime + " ms");
            System.err.println("\tTime in Young Generation GC: " + elapsedYoungCollectionsTime + " ms (" + youngCollections + " collections)");
            System.err.println("\tTime in Old Generation GC: " + elapsedOldCollectionsTime + " ms (" + oldCollections + " collections)");

            System.err.println("Garbage Generated in Young Generation: " + mebiBytes(totalYoungUsed) + " MiB");
            System.err.println("Garbage Generated in Survivor Generation: " + mebiBytes(totalSurvivorUsed) + " MiB");
            System.err.println("Garbage Generated in Old Generation: " + mebiBytes(totalOldUsed) + " MiB");

            System.err.println("Server Average CPU Load: " + ((float)elapsedProcessCPUTime * 100 / elapsedTime) + "/" + (100 * operatingSystem.getAvailableProcessors()));

//            System.err.println("Message Instances: " + instances);
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
}
