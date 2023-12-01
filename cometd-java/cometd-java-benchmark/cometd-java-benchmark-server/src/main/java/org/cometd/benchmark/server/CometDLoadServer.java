/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.benchmark.server;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.benchmark.Config;
import org.cometd.benchmark.MonitoringQueuedThreadPool;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.JacksonJSONContextServer;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.server.http.JSONHttpTransport;
import org.cometd.server.http.TransportContext;
import org.cometd.server.http.jakarta.CometDServlet;
import org.cometd.server.http.jetty.CometDHandler;
import org.cometd.server.websocket.common.AbstractWebSocketEndPoint;
import org.cometd.server.websocket.common.AbstractWebSocketTransport;
import org.cometd.server.websocket.jakarta.WebSocketTransport;
import org.cometd.server.websocket.jetty.JettyWebSocketTransport;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.toolchain.perf.HistogramSnapshot;
import org.eclipse.jetty.toolchain.perf.MeasureConverter;
import org.eclipse.jetty.toolchain.perf.PlatformMonitor;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

public class CometDLoadServer {
    private static final String MICROS = "\u00B5s";

    private final MonitoringQueuedThreadPool jettyThreadPool = new MonitoringQueuedThreadPool(0);
    private final MonitoringQueuedThreadPool cometdThreadPool = new MonitoringQueuedThreadPool(0);
    private final BayeuxServerImpl bayeuxServer = new BayeuxServerImpl();
    private final Server server = new Server(jettyThreadPool);
    private final MessageLatencyExtension messageLatencyExtension = new MessageLatencyExtension();
    private boolean interactive = true;
    private int port = 8080;
    private boolean tls = false;
    private int selectors = Runtime.getRuntime().availableProcessors();
    private int maxThreads = 256;
    private String transport = "jakarta";
    private boolean perMessageDeflate = false;
    private boolean statistics = true;
    private boolean latencies = true;
    private boolean longRequests = false;
    private RequestLatencyHandler requestLatencyHandler;
    private StatisticsHandler statisticsHandler;

    public static void main(String[] args) throws Exception {
        CometDLoadServer server = new CometDLoadServer();
        parseArguments(args, server);
        server.run();
    }

    private static void parseArguments(String[] args, CometDLoadServer server) {
        for (String arg : args) {
            if (arg.equals("--auto")) {
                server.interactive = false;
            } else if (arg.startsWith("--port=")) {
                server.port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.equals("--tls")) {
                server.tls = true;
            } else if (arg.startsWith("--selectors=")) {
                server.selectors = Integer.parseInt(arg.substring("--selectors=".length()));
            } else if (arg.startsWith("--maxThreads=")) {
                server.maxThreads = Integer.parseInt(arg.substring("--maxThreads=".length()));
            } else if (arg.startsWith("--transports=")) {
                server.transport = arg.substring("--transports=".length());
            } else if (arg.equals("--permessage-deflate")) {
                server.perMessageDeflate = true;
            } else if (arg.equals("--statistics")) {
                server.statistics = true;
            } else if (arg.equals("--latencies")) {
                server.latencies = true;
            } else if (arg.equals("--longRequests")) {
                server.longRequests = true;
            }
        }
    }

    public void run() throws Exception {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

        int port = this.port;
        if (interactive) {
            System.err.printf("listen port [%d]: ", port);
            String value = console.readLine().trim();
            if (value.isEmpty()) {
                value = String.valueOf(port);
            }
            port = Integer.parseInt(value);
        }

        boolean tls = this.tls;
        if (interactive) {
            System.err.printf("use tls [%b]: ", tls);
            String value = console.readLine().trim();
            if (value.isEmpty()) {
                value = String.valueOf(tls);
            }
            tls = Boolean.parseBoolean(value);
        }

        int selectors = this.selectors;
        if (interactive) {
            System.err.printf("selectors [%d]: ", selectors);
            String value = console.readLine().trim();
            if (value.isEmpty()) {
                value = String.valueOf(selectors);
            }
            selectors = Integer.parseInt(value);
        }

        int maxThreads = this.maxThreads;
        if (interactive) {
            maxThreads = Integer.parseInt(System.getProperty("cometd.threads", String.valueOf(maxThreads)));
            System.err.printf("max threads [%d]: ", maxThreads);
            String value = console.readLine().trim();
            if (value.isEmpty()) {
                value = String.valueOf(maxThreads);
            }
            maxThreads = Integer.parseInt(value);
        }
        jettyThreadPool.setMaxThreads(maxThreads);
        cometdThreadPool.setMaxThreads(maxThreads);
        // The BayeuxServer executor uses PEC mode only.
        cometdThreadPool.setReservedThreads(0);

        boolean perMessageDeflate = this.perMessageDeflate;
        if (interactive) {
            System.err.printf("enable permessage-deflate extension [%b]: ", perMessageDeflate);
            String value = console.readLine().trim();
            if (value.isEmpty()) {
                value = String.valueOf(perMessageDeflate);
            }
            perMessageDeflate = Boolean.parseBoolean(value);
        }

        String availableTransports = "jakarta | jetty";
        String transport = this.transport;
        if (interactive) {
            System.err.printf("transports (%s) [%s]: ", availableTransports, transport);
            String value = console.readLine().trim();
            if (value.isEmpty()) {
                value = transport;
            }
            transport = value;
        }
        AbstractWebSocketTransport wsTransport = switch (transport) {
            case "jakarta" -> new WebSocketTransport(bayeuxServer) {
                @Override
                protected void writeComplete(AbstractWebSocketEndPoint.Context context, List<ServerMessage> messages) {
                    messageLatencyExtension.complete(messages);
                }
            };
            case "jetty" -> new JettyWebSocketTransport(bayeuxServer) {
                @Override
                protected void writeComplete(AbstractWebSocketEndPoint.Context context, List<ServerMessage> messages) {
                    messageLatencyExtension.complete(messages);
                }
            };
            default -> throw new IllegalArgumentException("Invalid transport: " + transport);
        };
        wsTransport.setOption(AbstractWebSocketTransport.ENABLE_EXTENSION_PREFIX_OPTION + "permessage-deflate", perMessageDeflate);
        bayeuxServer.addTransport(wsTransport);
        bayeuxServer.addTransport(new JSONHttpTransport(bayeuxServer) {
            @Override
            protected void writeComplete(TransportContext context, List<ServerMessage> messages) {
                messageLatencyExtension.complete(messages);
            }
        });

        boolean statistics = this.statistics;
        if (interactive) {
            System.err.printf("record statistics [%b]: ", statistics);
            String value = console.readLine().trim();
            if (value.isEmpty()) {
                value = String.valueOf(statistics);
            }
            statistics = Boolean.parseBoolean(value);
        }

        boolean latencies = this.latencies;
        if (interactive) {
            System.err.printf("record latencies [%b]: ", latencies);
            String value = console.readLine().trim();
            if (value.isEmpty()) {
                value = String.valueOf(latencies);
            }
            latencies = Boolean.parseBoolean(value);
        }

        boolean longRequests = this.longRequests;
        if (interactive) {
            System.err.printf("detect long requests [%b]: ", longRequests);
            String value = console.readLine().trim();
            if (value.isEmpty()) {
                value = String.valueOf(longRequests);
            }
            longRequests = Boolean.parseBoolean(value);
        }

        // Setup JMX.
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbeanContainer);

        SslContextFactory.Server sslContextFactory = null;
        if (tls) {
            Path keyStoreFile = Paths.get("src/main/resources/keystore.p12");
            if (!Files.exists(keyStoreFile)) {
                throw new FileNotFoundException(keyStoreFile.toString());
            }
            sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStorePath(keyStoreFile.toString());
            sslContextFactory.setKeyStoreType("pkcs12");
            sslContextFactory.setKeyStorePassword("storepwd");
        }

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.setDelayDispatchUntilContent(true);
        ConnectionFactory http = new HttpConnectionFactory(httpConfiguration);
        HTTP2ServerConnectionFactory http2 = tls ? new HTTP2ServerConnectionFactory(httpConfiguration) : new HTTP2CServerConnectionFactory(httpConfiguration);
        ConnectionFactory[] factories = {http, http2};
        if (tls) {
            ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
            alpn.setDefaultProtocol(http.getProtocol());
            factories = AbstractConnectionFactory.getFactories(sslContextFactory, alpn, http, http2);
        }
        ServerConnector connector = new ServerConnector(server, null, null, null, 1, selectors, factories);
        // Make sure the OS is configured properly for load testing;
        // see http://cometd.org/documentation/howtos/loadtesting.
        connector.setAcceptQueueSize(2048);
        // Make sure the server timeout on a TCP connection is large.
        connector.setIdleTimeout(Config.META_CONNECT_TIMEOUT + 10 * Config.MAX_NETWORK_DELAY);
        connector.setPort(port);
        server.addConnector(connector);

        Handler.Wrapper handler = server;

        if (latencies) {
            requestLatencyHandler = new RequestLatencyHandler();
            handler.setHandler(requestLatencyHandler);
            handler = requestLatencyHandler;
        }

        if (longRequests) {
            RequestQoSHandler requestQoSHandler = new RequestQoSHandler();
            handler.setHandler(requestQoSHandler);
            handler = requestQoSHandler;
        }

        if (statistics) {
            statisticsHandler = new StatisticsHandler();
            handler.setHandler(statisticsHandler);
            handler = statisticsHandler;
        }

        // Add more handlers if needed.

        String cometdURLMapping = Config.COMETD_PATH + "/*";
        switch (transport) {
            case "jakarta" -> {
                ServletContextHandler context = new ServletContextHandler(Config.CONTEXT_PATH);
                handler.setHandler(context);
                context.getServletContext().setAttribute(BayeuxServer.ATTRIBUTE, bayeuxServer);
                context.setInitParameter(ServletContextHandler.MANAGED_ATTRIBUTES, BayeuxServer.ATTRIBUTE);

                JakartaWebSocketServletContainerInitializer.configure(context, null);

                // Set up the CometDServlet.
                CometDServlet cometServlet = new CometDServlet();
                ServletHolder cometdServletHolder = new ServletHolder(cometServlet);
                context.addServlet(cometdServletHolder, cometdURLMapping);
            }
            case "jetty" -> {
                ContextHandler context = new ContextHandler(Config.CONTEXT_PATH);
                handler.setHandler(context);
                context.getContext().setAttribute(BayeuxServer.ATTRIBUTE, bayeuxServer);
                context.setAttribute(ContextHandler.MANAGED_ATTRIBUTES, BayeuxServer.ATTRIBUTE);

                WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
                context.setHandler(wsHandler);

                wsHandler.setHandler(new CometDHandler());
            }
            default -> throw new IllegalArgumentException("Invalid transport: " + transport);
        }

        // Make sure the expiration timeout is large to avoid clients to timeout
        // This value must be several times larger than the client value
        // (e.g. 60 s on server vs 5 s on client) so that it's guaranteed that
        // it will be the client to dispose idle connections.
        bayeuxServer.setOption(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(10 * Config.MAX_NETWORK_DELAY));
        // Explicitly set the timeout value.
        bayeuxServer.setOption(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(Config.META_CONNECT_TIMEOUT));
        // Use the faster JSON parser/generator.
        bayeuxServer.setOption(AbstractServerTransport.JSON_CONTEXT_OPTION, JacksonJSONContextServer.class.getName());
        bayeuxServer.setOption(AbstractWebSocketTransport.COMETD_URL_MAPPING_OPTION, cometdURLMapping);

        bayeuxServer.addExtension(new AcknowledgedMessagesExtension());
        bayeuxServer.addExtension(messageLatencyExtension);

        bayeuxServer.setExecutor(cometdThreadPool);

        server.start();

        new StatisticsService(this);
    }

    public static class StatisticsService extends AbstractService {
        private final AutoLock lock = new AutoLock();
        private final PlatformMonitor monitor = new PlatformMonitor();
        private final CometDLoadServer server;

        private StatisticsService(CometDLoadServer server) {
            super(server.bayeuxServer, "statistics-service");
            this.server = server;
            addService("/service/statistics/start", "startStatistics");
            addService("/service/statistics/stop", "stopStatistics");
            addService("/service/statistics/exit", "exit");
        }

        @SuppressWarnings("unused")
        public void startStatistics(ServerSession remote, ServerMessage message) {
            // Multiple nodes must wait that initialization is completed
            try (AutoLock ignored = lock.lock()) {
                PlatformMonitor.Start start = monitor.start();
                if (start != null) {
                    System.err.println();
                    System.err.println(start);

                    server.jettyThreadPool.reset();
                    server.cometdThreadPool.reset();

                    if (server.statisticsHandler != null) {
                        server.statisticsHandler.reset();
                    }

                    if (server.requestLatencyHandler != null) {
                        server.requestLatencyHandler.reset();
                    }
                }
            }
        }

        @SuppressWarnings("unused")
        public void stopStatistics(ServerSession remote, ServerMessage message) {
            try (AutoLock ignored = lock.lock()) {
                PlatformMonitor.Stop stop = monitor.stop();
                if (stop != null) {
                    System.err.println(stop);

                    if (server.requestLatencyHandler != null) {
                        server.requestLatencyHandler.print();
                    }

                    if (server.statisticsHandler != null) {
                        int dispatched = server.statisticsHandler.getRequestTotal();
                        if (dispatched > 0) {
                            System.err.printf("Requests times (total/avg/max - stddev): %d/%d/%d ms - %d%n",
                                    server.statisticsHandler.getRequestTimeTotal(),
                                    ((Double)server.statisticsHandler.getRequestTimeMean()).longValue(),
                                    server.statisticsHandler.getRequestTimeMax(),
                                    ((Double)server.statisticsHandler.getRequestTimeStdDev()).longValue());
                            System.err.printf("Requests (total/failed/max - rate): %d/%d/%d - %d requests/s%n",
                                    dispatched,
                                    server.statisticsHandler.getResponses4xx() + server.statisticsHandler.getResponses5xx(),
                                    server.statisticsHandler.getRequestsActiveMax(),
                                    server.statisticsHandler.getRequestTotal() * 1000L / server.statisticsHandler.getStatisticsDuration().toMillis());
                        }
                    }

                    server.messageLatencyExtension.print();

                    System.err.println("========================================");
                    Config.printThreadPool("Jetty Thread Pool", server.jettyThreadPool);
                    Config.printThreadPool("CometD Thread Pool", server.cometdThreadPool);

                    System.err.println();
                }
            }
        }

        @SuppressWarnings("unused")
        public void exit(ServerSession remote, ServerMessage message) {
            remote.disconnect();
            // Cannot stop the server from a threadPool thread.
            new Thread(() -> {
                try {
                    if (!server.interactive) {
                        server.server.stop();
                    }
                } catch (Throwable ignored) {
                }
            }).start();
        }
    }

    private static class RequestQoSHandler extends Handler.Wrapper {
        private final long maxRequestTime = 500;
        private final AtomicLong requestIds = new AtomicLong();
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(50);

        @Override
        protected void doStop() throws Exception {
            super.doStop();
            scheduler.shutdown();
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            long requestId = requestIds.incrementAndGet();
            AtomicBoolean longRequest = new AtomicBoolean(false);
            Thread thread = Thread.currentThread();
            ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() -> {
                longRequest.set(true);
                onLongRequestDetected(requestId, request, thread);
            }, maxRequestTime, maxRequestTime, TimeUnit.MILLISECONDS);
            Request.addCompletionListener(request, x ->
            {
                long elapsed = NanoTime.since(request.getBeginNanoTime());
                task.cancel(false);
                if (longRequest.get()) {
                    onLongRequestEnded(requestId, elapsed);
                }
            });
            return super.handle(request, response, callback);
        }

        private void onLongRequestDetected(long requestId, Request request, Thread thread) {
            try {
                long begin = NanoTime.now();
                StackTraceElement[] stackFrames = thread.getStackTrace();
                StringBuilder builder = new StringBuilder();
                formatRequest(request, builder);
                builder.append(thread).append("\n");
                formatStackFrames(stackFrames, builder);
                System.err.printf("Request #%d is too slow (> %d ms)%n%s%n", requestId, maxRequestTime, builder);
                System.err.printf("Request #%d printed in %d %s%n", requestId, TimeUnit.NANOSECONDS.toMicros(NanoTime.since(begin)), MICROS);
            } catch (Exception x) {
                x.printStackTrace();
            }
        }

        private void formatRequest(Request request, StringBuilder builder) {
            builder.append(request.getHttpURI()).append("\n");
            for (HttpField field : request.getHeaders()) {
                String name = field.getName();
                builder.append(name).append("=").append(field.getValueList()).append("\n");
            }
            builder.append(Request.getRemoteAddr(request)).append(":").append(Request.getRemotePort(request)).append(" => ");
            builder.append(Request.getLocalAddr(request)).append(":").append(Request.getLocalPort(request)).append("\n");
        }

        private void onLongRequestEnded(long requestId, long time) {
            System.err.println("Request #" + requestId + " lasted " + TimeUnit.NANOSECONDS.toMillis(time) + " ms");
        }

        private void formatStackFrames(StackTraceElement[] stackFrames, StringBuilder builder) {
            for (int i = 0; i < stackFrames.length; ++i) {
                StackTraceElement stackFrame = stackFrames[i];
                builder.append(" ".repeat(i));
                builder.append(stackFrame).append("\n");
            }
        }
    }

    private static class RequestLatencyHandler extends Handler.Wrapper implements MeasureConverter {
        private final java.util.Collection<Histogram> allHistograms = new CopyOnWriteArrayList<>();
        private final ThreadLocal<Histogram> histogram = ThreadLocal.withInitial(() -> {
            Histogram histogram = new Histogram(TimeUnit.MINUTES.toNanos(1), 3);
            allHistograms.add(histogram);
            return histogram;
        });

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            Request.addCompletionListener(request, x ->
            {
                if (!request.getHeaders().contains(HttpHeader.UPGRADE)) {
                    updateLatencies(request.getBeginNanoTime(), NanoTime.now());
                }
            });
            return super.handle(request, response, callback);
        }

        @Override
        public long convert(long measure) {
            return TimeUnit.NANOSECONDS.toMicros(measure);
        }

        private void reset() {
            allHistograms.forEach(Histogram::reset);
        }

        private void updateLatencies(long begin, long end) {
            histogram.get().recordValue(end - begin);
        }

        private void print() {
            Histogram histogram = allHistograms.stream().reduce(new Histogram(TimeUnit.MINUTES.toNanos(1), 3), (h1, h2) -> {
                h1.add(h2);
                return h1;
            });
            if (histogram.getTotalCount() > 0) {
                System.err.println(new HistogramSnapshot(histogram, 20, "Requests - Latency", MICROS, this));
            }
        }
    }

    private static class MessageLatencyExtension implements BayeuxServer.Extension, MeasureConverter {
        private static final String SERVER_TIME_FIELD = "serverTime";

        private final Recorder latencies = new Recorder(TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MINUTES.toNanos(1), 3);

        @Override
        public boolean rcv(ServerSession session, ServerMessage.Mutable message) {
            if (message.getChannel().startsWith(Config.CHANNEL_PREFIX)) {
                String id = (String)message.getDataAsMap().get(Config.ID_FIELD);
                if (id != null) {
                    message.put(SERVER_TIME_FIELD, NanoTime.now());
                }
            }
            return true;
        }

        private void complete(List<ServerMessage> messages) {
            for (ServerMessage message : messages) {
                if (message.getChannel().startsWith(Config.CHANNEL_PREFIX)) {
                    String id = (String)message.getDataAsMap().get(Config.ID_FIELD);
                    if (id != null) {
                        Long serverTime = (Long)message.get(SERVER_TIME_FIELD);
                        if (serverTime != null) {
                            latencies.recordValue(NanoTime.since(serverTime));
                        }
                    }
                }
            }
        }

        @Override
        public long convert(long measure) {
            return TimeUnit.NANOSECONDS.toMicros(measure);
        }

        private void print() {
            Histogram histogram = latencies.getIntervalHistogram();
            if (histogram.getTotalCount() > 0) {
                System.err.println("========================================");
                System.err.println(new HistogramSnapshot(histogram, 20, "Messages - Processing", MICROS, this));
            }
        }
    }
}
