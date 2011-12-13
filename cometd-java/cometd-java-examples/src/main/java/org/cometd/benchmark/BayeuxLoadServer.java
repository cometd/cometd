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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometdServlet;
import org.cometd.server.JacksonJSONContextServer;
import org.cometd.websocket.server.WebSocketTransport;
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
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class BayeuxLoadServer
{
    public static void main(String[] args) throws Exception
    {
        BayeuxLoadServer server = new BayeuxLoadServer();
        server.run();
    }

    public void run() throws Exception
    {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

        int port = 8080;
        System.err.printf("listen port [%d]: ", port);
        String value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(port);
        port = Integer.parseInt(value);

        boolean ssl = false;
        System.err.printf("use ssl [%b]: ", ssl);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(ssl);
        ssl = Boolean.parseBoolean(value);

        int acceptors = Runtime.getRuntime().availableProcessors();
        System.err.printf("acceptors [%d]: ", acceptors);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(acceptors);
        acceptors = Integer.parseInt(value);

        int maxThreads = 256;
        System.err.printf("max threads [%d]: ", maxThreads);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(maxThreads);
        maxThreads = Integer.parseInt(value);

        boolean stats = true;
        System.err.printf("record statistics [%b]: ", stats);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(stats);
        stats = Boolean.parseBoolean(value);

        boolean reqs = true;
        System.err.printf("record latencies [%b]: ", reqs);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(reqs);
        reqs = Boolean.parseBoolean(value);

        boolean qos = false;
        System.err.printf("detect long requests [%b]: ", qos);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(qos);
        qos = Boolean.parseBoolean(value);

        Server server = new Server();

        // Setup JMX
        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.getContainer().addEventListener(mbContainer);
        server.addBean(mbContainer);

        SelectChannelConnector connector;
        if (ssl)
        {
            SslSelectChannelConnector sslConnector = new SslSelectChannelConnector();
            File keyStoreFile = new File("src/main/resources/keystore.jks");
            if (!keyStoreFile.exists())
                throw new FileNotFoundException(keyStoreFile.getAbsolutePath());
            SslContextFactory sslContextFactory = sslConnector.getSslContextFactory();
            sslContextFactory.setKeyStorePath(keyStoreFile.getAbsolutePath());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");
//            sslConnector.setUseDirectBuffers(true);
            connector = sslConnector;
        }
        else
        {
            connector = new SelectChannelConnector();
        }
        // Make sure the OS is configured properly for load testing;
        // see http://cometd.org/documentation/howtos/loadtesting
        connector.setAcceptQueueSize(2048);
        // Make sure the server timeout on a TCP connection is large
        connector.setMaxIdleTime(240000);
        connector.setAcceptors(acceptors);
        connector.setPort(port);
        server.addConnector(connector);

        MonitoringQueuedThreadPool jettyThreadPool = new MonitoringQueuedThreadPool(maxThreads);
//        ExecutorThreadPool jettyThreadPool = new ExecutorThreadPool(maxThreads, maxThreads, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        server.setThreadPool(jettyThreadPool);

        HandlerWrapper handler = server;

        RequestLatencyHandler requestLatencyHandler = null;
        if (reqs)
        {
            requestLatencyHandler = new RequestLatencyHandler();
            handler.setHandler(requestLatencyHandler);
            handler = requestLatencyHandler;
        }

        if (qos)
        {
            RequestQoSHandler requestQoSHandler = new RequestQoSHandler();
            handler.setHandler(requestQoSHandler);
            handler = requestQoSHandler;
        }

        StatisticsHandler statisticsHandler = null;
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
        ServletHolder cometdServletHolder = new ServletHolder(cometServlet);
        // Make sure the expiration timeout is large to avoid clients to timeout
        // This value must be several times larger than the client value
        // (e.g. 60 s on server vs 5 s on client) so that it's guaranteed that
        // it will be the client to dispose idle connections.
        cometdServletHolder.setInitParameter(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(60000));
        // Explicitly set the timeout value
        cometdServletHolder.setInitParameter(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(30000));
        // Use the faster JSON parser/generator
        cometdServletHolder.setInitParameter(BayeuxServerImpl.JSON_CONTEXT, JacksonJSONContextServer.class.getName());
        context.addServlet(cometdServletHolder, cometServletPath + "/*");

        server.start();

        BayeuxServerImpl bayeux = cometServlet.getBayeux();

        MonitoringThreadPoolExecutor websocketThreadPool = new MonitoringThreadPoolExecutor(maxThreads, jettyThreadPool.getMaxIdleTimeMs(), TimeUnit.MILLISECONDS, new ThreadPoolExecutor.AbortPolicy());

        LoadWebSocketTransport webSocketTransport = new LoadWebSocketTransport(bayeux, websocketThreadPool);
        webSocketTransport.init();
        bayeux.addTransport(webSocketTransport);
        bayeux.setAllowedTransports("websocket", "long-polling");

        new StatisticsService(bayeux, jettyThreadPool, websocketThreadPool, statisticsHandler, requestLatencyHandler);
    }

    public static class StatisticsService extends AbstractService
    {
        private final BenchmarkHelper helper = new BenchmarkHelper();
        private final MonitoringQueuedThreadPool jettyThreadPool;
        private final MonitoringThreadPoolExecutor websocketThreadPool;
        private final StatisticsHandler statisticsHandler;
        private final RequestLatencyHandler requestLatencyHandler;

        private StatisticsService(BayeuxServer bayeux, MonitoringQueuedThreadPool jettyThreadPool, MonitoringThreadPoolExecutor websocketThreadPool, StatisticsHandler statisticsHandler, RequestLatencyHandler requestLatencyHandler)
        {
            super(bayeux, "statistics-service");
            this.jettyThreadPool = jettyThreadPool;
            this.websocketThreadPool = websocketThreadPool;
            this.statisticsHandler = statisticsHandler;
            this.requestLatencyHandler = requestLatencyHandler;
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
                    if (jettyThreadPool != null)
                        jettyThreadPool.reset();
                    if (websocketThreadPool != null)
                        websocketThreadPool.reset();

                    if (statisticsHandler != null)
                        statisticsHandler.statsReset();

                    if (requestLatencyHandler != null)
                    {
                        requestLatencyHandler.reset();
                        requestLatencyHandler.doNotTrackCurrentRequest();
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
                    if (requestLatencyHandler != null)
                    {
                        requestLatencyHandler.print();
                        requestLatencyHandler.doNotTrackCurrentRequest();
                    }

                    if (statisticsHandler != null)
                    {
                        System.err.printf("Requests times (total/avg/max - stddev): %d/%d/%d ms - %d%n",
                                statisticsHandler.getDispatchedTimeTotal(),
                                ((Double)statisticsHandler.getDispatchedTimeMean()).longValue(),
                                statisticsHandler.getDispatchedTimeMax(),
                                ((Double)statisticsHandler.getDispatchedTimeStdDev()).longValue());
                        System.err.printf("Requests (total/failed/max - rate): %d/%d/%d - %d requests/s%n",
                                statisticsHandler.getDispatched(),
                                statisticsHandler.getResponses4xx() + statisticsHandler.getResponses5xx(),
                                statisticsHandler.getDispatchedActiveMax(),
                                statisticsHandler.getStatsOnMs() == 0 ? -1 : statisticsHandler.getDispatched() * 1000L / statisticsHandler.getStatsOnMs());
                    }

                    if (jettyThreadPool != null)
                    {
                        System.err.printf("Jetty Thread Pool - Concurrent Threads max = %d | Queue Size max = %d | Queue Latency avg/max = %d/%d ms%n",
                                jettyThreadPool.getMaxActiveThreads(),
                                jettyThreadPool.getMaxQueueSize(),
                                TimeUnit.NANOSECONDS.toMillis(jettyThreadPool.getAverageQueueLatency()),
                                TimeUnit.NANOSECONDS.toMillis(jettyThreadPool.getMaxQueueLatency()));
                    }
                    if (websocketThreadPool != null)
                    {
                        System.err.printf("WebSocket Thread Pool - Concurrent Threads max = %d | Queue Size max = %d | Queue Latency avg/max = %d/%d ms%n",
                                websocketThreadPool.getMaxActiveThreads(),
                                websocketThreadPool.getMaxQueueSize(),
                                TimeUnit.NANOSECONDS.toMillis(websocketThreadPool.getAverageQueueLatency()),
                                TimeUnit.NANOSECONDS.toMillis(websocketThreadPool.getMaxQueueLatency()));
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
            for (Enumeration<String> headers = request.getHeaderNames(); headers.hasMoreElements(); )
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
        private final AtomicLong requests = new AtomicLong();
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
            requests.incrementAndGet();
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
            requests.set(0);
            minLatency.set(Long.MAX_VALUE);
            maxLatency.set(0);
            totLatency.set(0);
            latencies.clear();
        }

        private void updateLatencies(long begin, long end)
        {
            // Latencies are in nanoseconds, but microsecond accuracy is enough
            long latency = TimeUnit.MICROSECONDS.toNanos(TimeUnit.NANOSECONDS.toMicros(end - begin));
            Atomics.updateMin(minLatency, latency);
            Atomics.updateMax(maxLatency, latency);
            totLatency.addAndGet(latency);
            AtomicLong count = latencies.get(latency);
            if (count == null)
            {
                count = new AtomicLong();
                AtomicLong existing = latencies.put(latency, count);
                if (existing != null)
                    count = existing;
            }
            count.incrementAndGet();
        }

        private void print()
        {
            if (latencies.size() > 1)
            {
                long requestCount = requests.get();

                // Needs to be sorted in order to calculate the median (aka latency at 50th percentile)
                Map<Long, AtomicLong> sortedLatencies = new TreeMap<Long, AtomicLong>(latencies);
                latencies.clear();

                long requests = 0;
                long maxLatencyBucketFrequency = 0;
                long previousLatency = 0;
                long latencyAt50thPercentile = 0;
                long latencyAt99thPercentile = 0;
                long[] latencyBucketFrequencies = new long[20];
                long minLatency = this.minLatency.get();
                long latencyRange = maxLatency.get() - minLatency;
                for (Iterator<Map.Entry<Long, AtomicLong>> entries = sortedLatencies.entrySet().iterator(); entries.hasNext();)
                {
                    Map.Entry<Long, AtomicLong> entry = entries.next();
                    long latency = entry.getKey();
                    Long bucketIndex = latencyRange == 0 ? 0 : (latency - minLatency) * latencyBucketFrequencies.length / latencyRange;
                    int index = bucketIndex.intValue() == latencyBucketFrequencies.length ? latencyBucketFrequencies.length - 1 : bucketIndex.intValue();
                    long value = entry.getValue().get();
                    requests += value;
                    latencyBucketFrequencies[index] += value;
                    if (latencyBucketFrequencies[index] > maxLatencyBucketFrequency)
                        maxLatencyBucketFrequency = latencyBucketFrequencies[index];
                    if (latencyAt50thPercentile == 0 && requests > requestCount / 2)
                        latencyAt50thPercentile = (previousLatency + latency) / 2;
                    if (latencyAt99thPercentile == 0 && requests > requestCount - requestCount / 100)
                        latencyAt99thPercentile = (previousLatency + latency) / 2;
                    previousLatency = latency;
                    entries.remove();
                }

                System.err.println("Requests - Latency Distribution Curve (X axis: Frequency, Y axis: Latency):");
                double percentile = 0.0;
                for (int i = 0; i < latencyBucketFrequencies.length; ++i)
                {
                    long latencyBucketFrequency = latencyBucketFrequencies[i];
                    int value = maxLatencyBucketFrequency == 0 ? 0 : Math.round(latencyBucketFrequency * (float)latencyBucketFrequencies.length / maxLatencyBucketFrequency);
                    if (value == latencyBucketFrequencies.length)
                        value = value - 1;
                    for (int j = 0; j < value; ++j)
                        System.err.print(" ");
                    System.err.print("@");
                    for (int j = value + 1; j < latencyBucketFrequencies.length; ++j)
                        System.err.print(" ");
                    System.err.print("  _  ");
                    System.err.print(TimeUnit.NANOSECONDS.toMillis((latencyRange * (i + 1) / latencyBucketFrequencies.length) + minLatency));
                    System.err.printf(" ms (%d, %.2f%%)", latencyBucketFrequency, (100.0 * latencyBucketFrequency / requestCount));
                    double last = percentile;
                    percentile += (100.0 * latencyBucketFrequency / requestCount);
                    if (last < 50.0 && percentile >= 50.0)
                        System.err.print(" ^50%");
                    if (last < 85.0 && percentile >= 85.0)
                        System.err.print(" ^85%");
                    if (last < 95.0 && percentile >= 95.0)
                        System.err.print(" ^95%");
                    if (last < 99.0 && percentile >= 99.0)
                        System.err.print(" ^99%");
                    if (last < 99.9 && percentile >= 99.9)
                        System.err.print(" ^99.9%");
                    System.err.println();
                }

                System.err.printf("Requests - Latency 50th%%/99th%% = %d/%d ms%n",
                        TimeUnit.NANOSECONDS.toMillis(latencyAt50thPercentile),
                        TimeUnit.NANOSECONDS.toMillis(latencyAt99thPercentile));
            }
        }

        public void doNotTrackCurrentRequest()
        {
            currentEnabled.set(false);
        }
    }

    public static class LoadWebSocketTransport extends WebSocketTransport
    {
        private final Executor executor;

        public LoadWebSocketTransport(BayeuxServerImpl bayeux, Executor executor)
        {
            super(bayeux);
            this.executor = executor;
        }

        @Override
        protected Executor newExecutor()
        {
            return executor;
        }
    }
}
