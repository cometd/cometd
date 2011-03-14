package org.cometd.client;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.CometdServlet;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * @version $Revision: 781 $ $Date: 2009-10-08 19:34:08 +1100 (Thu, 08 Oct 2009) $
 */
public class BayeuxLoadServer
{
    private static StatisticsHandler statisticsHandler;
    private static RequestQoSHandler requestQoSHandler;
    private static RequestLatencyHandler requestLatencyHandler;

    public static void main(String[] args) throws Exception
    {
        boolean ssl = false;
        boolean qos = false;
        boolean stats = false;
        boolean reqs = false;
        int port = 8080;

        for (String arg : args)
        {
            ssl |= "--ssl".equals(arg);
            qos |= "--qos".equals(arg);
            stats |= "--stats".equals(arg);
            reqs |= "--reqs".equals(arg);
            if (!arg.startsWith("--"))
                port = Integer.parseInt(arg);
        }

        Server server = new Server();

        // Setup JMX
        MBeanContainer mbContainer=new
        MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.getContainer().addEventListener(mbContainer);
        server.addBean(mbContainer);
        mbContainer.addBean(Log.getLog());

        SelectChannelConnector connector;
        if (ssl)
        {
            SslSelectChannelConnector sslConnector = new SslSelectChannelConnector();
            File keyStoreFile = new File("src/test/resources/keystore.jks");
            if (!keyStoreFile.exists())
                throw new FileNotFoundException(keyStoreFile.getAbsolutePath());
            sslConnector.setKeystore(keyStoreFile.getAbsolutePath());
            sslConnector.setPassword("storepwd");
            sslConnector.setKeyPassword("keypwd");
//            sslConnector.setUseDirectBuffers(true);
            connector = sslConnector;
        }
        else
        {
            connector = new SelectChannelConnector();
        }
        // Make sure the OS is configured properly for load testing;
        // see http://docs.codehaus.org/display/JETTY/HighLoadServers
        connector.setAcceptQueueSize(2048);
        // Make sure the server timeout on a TCP connection is large
        connector.setMaxIdleTime(240000);
        connector.setPort(port);
        server.addConnector(connector);

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(256);
        server.setThreadPool(threadPool);

        HandlerWrapper handler = server;

        if (reqs)
        {
            requestLatencyHandler = new RequestLatencyHandler();
            handler.setHandler(requestLatencyHandler);
            handler = requestLatencyHandler;
        }

        if (qos)
        {
            requestQoSHandler = new RequestQoSHandler();
            handler.setHandler(requestQoSHandler);
            handler = requestQoSHandler;
        }

        if (stats)
        {
            statisticsHandler = new StatisticsHandler();
            handler.setHandler(statisticsHandler);
            handler = statisticsHandler;
        }

        // Add more handlers if needed

        String contextPath = "";
        ServletContextHandler context = new ServletContextHandler(handler, contextPath, ServletContextHandler.SESSIONS);

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

    public static class StatisticsService extends AbstractService
    {
        private final StatisticsHelper helper = new StatisticsHelper();

        private StatisticsService(BayeuxServer bayeux)
        {
            super(bayeux, "statistics-service");
            addService("/service/statistics/start", "startStatistics");
            addService("/service/statistics/stop", "stopStatistics");
        }

        public void startStatistics(ServerSession remote, Message message)
        {
            // Multiple nodes must wait that initialization is completed
            synchronized (this)
            {
                boolean started = helper.startStatistics();
                if (started)
                {
                    if (statisticsHandler != null)
                    {
                        statisticsHandler.statsReset();
                    }

                    if (requestLatencyHandler != null)
                    {
                        requestLatencyHandler.reset();
                        requestLatencyHandler.disableCurrent();
                    }
                }
            }
        }

        public void stopStatistics(ServerSession remote, Message message) throws Exception
        {
            synchronized (this)
            {
                boolean stopped = helper.stopStatistics();
                if (stopped)
                {
                    if (statisticsHandler != null)
                    {
                        System.err.println("Requests (total/failed/max): " + statisticsHandler.getDispatched() + "/" +
                                (statisticsHandler.getResponses4xx() + statisticsHandler.getResponses5xx()) + "/" +
                                statisticsHandler.getDispatchedActiveMax());
                        System.err.println("Requests times (total/avg/max - stddev): " +
                                statisticsHandler.getDispatchedTimeTotal() + "/" +
                                ((Double)statisticsHandler.getDispatchedTimeMean()).longValue() + "/" +
                                statisticsHandler.getDispatchedTimeMax() + " ms - " +
                                ((Double)statisticsHandler.getDispatchedTimeStdDev()).longValue());
                    }

                    if (requestLatencyHandler != null)
                    {
                        requestLatencyHandler.print();
                        requestLatencyHandler.disableCurrent();
                    }
                }
            }
        }
    }

    private static class RequestQoSHandler extends HandlerWrapper
    {
        private final long maxRequestTime = 500;
        private final AtomicLong requestIds = new AtomicLong();
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(50);

        @Override
        protected void doStop() throws Exception
        {
            super.doStop();
            scheduler.shutdown();
        }

        @Override
        public void handle(String target, Request request, final HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            final long requestId = requestIds.incrementAndGet();
            final AtomicBoolean longRequest = new AtomicBoolean(false);
            final Thread thread = Thread.currentThread();
            ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(new Runnable()
            {
                public void run()
                {
                    longRequest.set(true);
                    onLongRequestDetected(requestId, httpRequest, thread);
                }
            }, maxRequestTime, maxRequestTime, TimeUnit.MILLISECONDS);
            long start = System.nanoTime();
            try
            {
                super.handle(target, request, httpRequest, httpResponse);
            }
            finally
            {
                long end = System.nanoTime();
                task.cancel(false);
                if (longRequest.get())
                {
                    onLongRequestEnded(requestId, end - start);
                }
            }
        }

        private void onLongRequestDetected(long requestId, HttpServletRequest request, Thread thread)
        {
            try
            {
                long begin = System.nanoTime();
                StackTraceElement[] stackFrames = thread.getStackTrace();
                StringBuilder builder = new StringBuilder();
                formatRequest(request, builder);
                builder.append(thread).append("\n");
                formatStackFrames(stackFrames, builder);
                System.err.println("Request #" + requestId + " is too slow (> " + maxRequestTime + " ms)\n" + builder);
                long end = System.nanoTime();
                System.err.println("Request #" + requestId + " printed in " + TimeUnit.NANOSECONDS.toMicros(end - begin) + " \u00B5s");
            }
            catch (Exception x)
            {
                x.printStackTrace();
            }
        }

        private void formatRequest(HttpServletRequest request, StringBuilder builder)
        {
            builder.append(request.getRequestURI()).append("\n");
            for (Enumeration<String> headers = request.getHeaderNames(); headers.hasMoreElements();)
                {
                    String name = headers.nextElement();
                    builder.append(name).append("=").append(Collections.list(request.getHeaders(name))).append("\n");
                }
            builder.append(request.getRemoteAddr()).append(":").append(request.getRemotePort()).append(" => ");
            builder.append(request.getLocalAddr()).append(":").append(request.getLocalPort()).append("\n");
        }

        private void onLongRequestEnded(long requestId, long time)
        {
            System.err.println("Request #" + requestId + " lasted " + TimeUnit.NANOSECONDS.toMillis(time) + " ms");
        }

        private void formatStackFrames(StackTraceElement[] stackFrames, StringBuilder builder)
        {
            for (int i = 0; i < stackFrames.length; ++i)
            {
                StackTraceElement stackFrame = stackFrames[i];
                for (int j = 0; j < i; ++j)
                    builder.append(" ");
                builder.append(stackFrame).append("\n");
            }
        }
    }

    private static class RequestLatencyHandler extends HandlerWrapper
    {
        private final AtomicLong minLatency = new AtomicLong();
        private final AtomicLong maxLatency = new AtomicLong();
        private final AtomicLong totLatency = new AtomicLong();
        private final ConcurrentMap<Long, AtomicLong> latencies = new ConcurrentHashMap<Long, AtomicLong>();
        private final ThreadLocal<Boolean> currentEnabled = new ThreadLocal<Boolean>()
        {
            @Override
            protected Boolean initialValue()
            {
                return Boolean.TRUE;
            }
        };

        @Override
        public void handle(String target, Request request, final HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            long begin = System.nanoTime();
            try
            {
                super.handle(target, request, httpRequest, httpResponse);
            }
            finally
            {
                long end = System.nanoTime();
                if (currentEnabled.get())
                {
                    updateLatencies(begin, end);
                }
                else
                {
                    currentEnabled.set(true);
                }
            }
        }

        private void reset()
        {
            minLatency.set(Long.MAX_VALUE);
            maxLatency.set(0);
            totLatency.set(0);
            latencies.clear();
        }

        private void updateLatencies(long begin, long end)
        {
            long latency = end - begin;
            updateMin(minLatency, latency);
            updateMax(maxLatency, latency);
            totLatency.addAndGet(latency);
            latencies.putIfAbsent(latency, new AtomicLong(0));
            latencies.get(latency).incrementAndGet();
        }

        private void updateMin(AtomicLong min, long value)
        {
            long oldValue = min.get();
            while (value < oldValue)
            {
                if (min.compareAndSet(oldValue, value))
                    break;
                oldValue = min.get();
            }
        }

        private void updateMax(AtomicLong max, long value)
        {
            long oldValue = max.get();
            while (value > oldValue)
            {
                if (max.compareAndSet(oldValue, value))
                    break;
                oldValue = max.get();
            }
        }

        private void print()
        {
            if (latencies.size() > 1)
            {
                long maxLatencyBucketFrequency = 0L;
                long[] latencyBucketFrequencies = new long[20];
                long minLatency = this.minLatency.get();
                long latencyRange = maxLatency.get() - minLatency;
                for (Iterator<Map.Entry<Long, AtomicLong>> entries = latencies.entrySet().iterator(); entries.hasNext();)
                {
                    Map.Entry<Long, AtomicLong> entry = entries.next();
                    long latency = entry.getKey();
                    Long bucketIndex = latencyRange == 0 ? 0 : (latency - minLatency) * latencyBucketFrequencies.length / latencyRange;
                    int index = bucketIndex.intValue() == latencyBucketFrequencies.length ? latencyBucketFrequencies.length - 1 : bucketIndex.intValue();
                    long value = entry.getValue().get();
                    latencyBucketFrequencies[index] += value;
                    if (latencyBucketFrequencies[index] > maxLatencyBucketFrequency) maxLatencyBucketFrequency = latencyBucketFrequencies[index];
                    entries.remove();
                }

                System.err.println("Requests - Latency Distribution Curve (X axis: Frequency, Y axis: Latency):");
                for (int i = 0; i < latencyBucketFrequencies.length; ++i)
                {
                    long latencyBucketFrequency = latencyBucketFrequencies[i];
                    int value = maxLatencyBucketFrequency == 0 ? 0 : Math.round(latencyBucketFrequency * (float) latencyBucketFrequencies.length / maxLatencyBucketFrequency);
                    if (value == latencyBucketFrequencies.length) value = value - 1;
                    for (int j = 0; j < value; ++j) System.err.print(" ");
                    System.err.print("@");
                    for (int j = value + 1; j < latencyBucketFrequencies.length; ++j) System.err.print(" ");
                    System.err.print("  _  ");
                    System.err.print(TimeUnit.NANOSECONDS.toMillis((latencyRange * (i + 1) / latencyBucketFrequencies.length) + minLatency));
                    System.err.println(" ms (" + latencyBucketFrequency + ")");
                }
            }
        }

        public void disableCurrent()
        {
            currentEnabled.set(false);
        }
    }
}
