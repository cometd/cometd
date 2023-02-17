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
package org.cometd.benchmark.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.LongAdder;
import jakarta.websocket.WebSocketContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.HdrHistogram.Histogram;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.benchmark.Atomics;
import org.cometd.benchmark.Config;
import org.cometd.benchmark.MonitoringQueuedThreadPool;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.http.jetty.JettyHttpClientTransport;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.websocket.jakarta.WebSocketTransport;
import org.cometd.client.websocket.jetty.JettyWebSocketTransport;
import org.cometd.common.JacksonJSONContextClient;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.jakarta.client.JakartaWebSocketClientContainerProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.toolchain.perf.HistogramSnapshot;
import org.eclipse.jetty.toolchain.perf.MeasureConverter;
import org.eclipse.jetty.toolchain.perf.PlatformMonitor;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class CometDLoadClient implements MeasureConverter {
    private static final String START_FIELD = "start";

    private final Collection<Histogram> allHistograms = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Histogram> histogram = ThreadLocal.withInitial(() -> {
        Histogram histogram = new Histogram(TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MINUTES.toNanos(1), 3);
        allHistograms.add(histogram);
        return histogram;
    });

    private final PlatformMonitor monitor = new PlatformMonitor();
    private final AtomicLong ids = new AtomicLong();
    private final List<LoadBayeuxClient> bayeuxClients = new BlockingArrayQueue<>();
    private final ConcurrentMap<String, ChannelId> channelIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, AtomicInteger> roomMap = new ConcurrentHashMap<>();
    private final AtomicLong start = new AtomicLong();
    private final AtomicLong end = new AtomicLong();
    private final AtomicLong responses = new AtomicLong();
    private final AtomicLong messages = new AtomicLong();
    private final AtomicLong minLatency = new AtomicLong();
    private final AtomicLong maxLatency = new AtomicLong();
    private final AtomicLong totLatency = new AtomicLong();
    private final AtomicStampedReference<String> maxTime = new AtomicStampedReference<>(null, 0);
    private final Map<String, AtomicStampedReference<Long>> sendTimes = new ConcurrentHashMap<>();
    private final Map<String, AtomicStampedReference<List<Long>>> arrivalTimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);
    private final MonitoringQueuedThreadPool threadPool = new MonitoringQueuedThreadPool(0);
    private final DynamicConnectionStatistics connectionStatistics = new DynamicConnectionStatistics();
    private HttpClient httpClient;
    private WebSocketClient webSocketClient;
    private WebSocketContainer webSocketContainer;
    private LatencyListener latencyListener;
    private HandshakeListener handshakeListener;
    private DisconnectListener disconnectListener;
    private boolean interactive = true;
    private String host = "localhost";
    private int port = 8080;
    private boolean tls = false;
    private int selectors = 1;
    private int maxThreads = 256;
    private ClientTransportType transport = ClientTransportType.LONG_POLLING;
    private boolean http2 = false;
    private boolean perMessageDeflate = false;
    private String context = Config.CONTEXT_PATH;
    private String channel = "/a";
    private int rooms = 100;
    private int roomsPerClient = 10;
    private boolean ackExtension = false;
    private int iterations = 5;
    private int clients = 1000;
    private int batches = 1000;
    private int batchSize = 10;
    private long batchPause = 10000;
    private int messageSize = 50;
    private boolean randomize = false;
    private String file = "./result.json";

    public static void main(String[] args) throws Exception {
        CometDLoadClient client = new CometDLoadClient();
        parseArguments(args, client);
        client.run();
    }

    private static void parseArguments(String[] args, CometDLoadClient client) {
        for (String arg : args) {
            if (arg.equals("--auto")) {
                client.interactive = false;
            } else if (arg.startsWith("--host=")) {
                client.host = arg.substring("--host=".length());
            } else if (arg.startsWith("--port=")) {
                client.port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.equals("--tls")) {
                client.tls = true;
            } else if (arg.startsWith("--selectors=")) {
                client.selectors = Integer.parseInt(arg.substring("--selectors=".length()));
            } else if (arg.startsWith("--maxThreads=")) {
                client.maxThreads = Integer.parseInt(arg.substring("--maxThreads=".length()));
            } else if (arg.startsWith("--transport=")) {
                client.transport = ClientTransportType.valueOf(arg.substring("--transport=".length()));
            } else if (arg.equals("--http2")) {
                client.http2 = true;
            } else if (arg.equals("--permessage-deflate")) {
                client.perMessageDeflate = true;
            } else if (arg.startsWith("--context=")) {
                client.context = arg.substring("--context=".length());
            } else if (arg.startsWith("--channel=")) {
                client.channel = arg.substring("--channel=".length());
            } else if (arg.startsWith("--rooms=")) {
                client.rooms = Integer.parseInt(arg.substring("--rooms=".length()));
            } else if (arg.startsWith("--roomsPerClient=")) {
                client.roomsPerClient = Integer.parseInt(arg.substring("--roomsPerClient=".length()));
            } else if (arg.equals("--ackExtension")) {
                client.ackExtension = true;
            } else if (arg.startsWith("--iterations=")) {
                client.iterations = Integer.parseInt(arg.substring("--iterations=".length()));
            } else if (arg.startsWith("--clients=")) {
                client.clients = Integer.parseInt(arg.substring("--clients=".length()));
            } else if (arg.startsWith("--batches=")) {
                client.batches = Integer.parseInt(arg.substring("--batches=".length()));
            } else if (arg.startsWith("--batchSize=")) {
                client.batchSize = Integer.parseInt(arg.substring("--batchSize=".length()));
            } else if (arg.startsWith("--batchPause=")) {
                client.batchPause = Long.parseLong(arg.substring("--batchPause=".length()));
            } else if (arg.startsWith("--messageSize=")) {
                client.messageSize = Integer.parseInt(arg.substring("--messageSize=".length()));
            } else if (arg.equals("--randomize")) {
                client.randomize = true;
            } else if (arg.startsWith("--file=")) {
                client.file = arg.substring("--file=".length());
            }
        }
    }

    public void run() throws Exception {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

        String host = this.host;
        if (interactive) {
            host = System.getProperty("cometd.server", host);
            System.err.printf("server [%s]: ", host);
            String value = console.readLine().trim();
            if (value.length() == 0) {
                value = host;
            }
            host = value;
        }

        int port = this.port;
        if (interactive) {
            port = Integer.parseInt(System.getProperty("cometd.port", String.valueOf(port)));
            System.err.printf("port [%d]: ", port);
            String value = console.readLine().trim();
            if (value.length() == 0) {
                value = String.valueOf(port);
            }
            port = Integer.parseInt(value);
        }

        boolean tls = this.tls;
        if (interactive) {
            System.err.printf("use tls [%b]: ", tls);
            String value = console.readLine().trim();
            if (value.length() == 0) {
                value = String.valueOf(tls);
            }
            tls = Boolean.parseBoolean(value);
        }

        int selectors = this.selectors;
        if (interactive) {
            System.err.printf("selectors [%d]: ", selectors);
            String value = console.readLine().trim();
            if (value.length() == 0) {
                value = String.valueOf(selectors);
            }
            selectors = Integer.parseInt(value);
        }

        int maxThreads = this.maxThreads;
        if (interactive) {
            maxThreads = Integer.parseInt(System.getProperty("cometd.threads", String.valueOf(maxThreads)));
            System.err.printf("max threads [%d]: ", maxThreads);
            String value = console.readLine().trim();
            if (value.length() == 0) {
                value = String.valueOf(maxThreads);
            }
            maxThreads = Integer.parseInt(value);
        }

        ClientTransportType transport = this.transport;
        if (interactive) {
            System.err.printf("transports:%n");
            for (ClientTransportType type : ClientTransportType.values()) {
                System.err.printf("  %d - %s%n", type.ordinal(), type.getName());
            }
            System.err.printf("transport [%d]: ", transport.ordinal());
            String value = console.readLine().trim();
            if (value.length() == 0) {
                value = String.valueOf(transport.ordinal());
            }
            transport = ClientTransportType.values()[Integer.parseInt(value)];
        }

        boolean http2 = this.http2;
        if (transport == ClientTransportType.LONG_POLLING) {
            if (interactive) {
                System.err.printf("use HTTP/2 [%b]: ", http2);
                String value = console.readLine().trim();
                if (value.length() == 0) {
                    value = String.valueOf(http2);
                }
                http2 = Boolean.parseBoolean(value);
            }
        } else {
            http2 = false;
        }

        boolean perMessageDeflate = this.perMessageDeflate;
        if (transport == ClientTransportType.JETTY_WEBSOCKET || transport == ClientTransportType.JAKARTA_WEBSOCKET) {
            if (interactive) {
                System.err.printf("enable permessage-deflate extension [%b]: ", perMessageDeflate);
                String value = console.readLine().trim();
                if (value.length() == 0) {
                    value = String.valueOf(perMessageDeflate);
                }
                perMessageDeflate = Boolean.parseBoolean(value);
            }
        } else {
            perMessageDeflate = false;
        }
        this.perMessageDeflate = perMessageDeflate;

        String contextPath = this.context;
        if (interactive) {
            System.err.printf("context [%s]: ", contextPath);
            String value = console.readLine().trim();
            if (value.length() == 0) {
                value = contextPath;
            }
            contextPath = value;
        }
        String url = (tls ? "https" : "http") + "://" + host + ":" + port + contextPath + Config.SERVLET_PATH;

        String channel = this.channel;
        if (interactive) {
            channel = System.getProperty("cometd.channel", channel);
            System.err.printf("channel [%s]: ", channel);
            String value = console.readLine().trim();
            if (value.length() == 0) {
                value = channel;
            }
            channel = value;
        }
        channel = Config.CHANNEL_PREFIX + (channel.startsWith("/") ? channel.substring(1) : channel);

        int rooms = this.rooms;
        if (interactive) {
            rooms = Integer.parseInt(System.getProperty("cometd.rooms", String.valueOf(rooms)));
            System.err.printf("rooms [%d]: ", rooms);
            String value = console.readLine().trim();
            if (value.length() == 0) {
                value = String.valueOf(rooms);
            }
            rooms = Integer.parseInt(value);
        }

        int roomsPerClient = this.roomsPerClient;
        if (interactive) {
            System.err.printf("rooms per client [%d]: ", roomsPerClient);
            String value = console.readLine().trim();
            if (value.length() == 0) {
                value = String.valueOf(roomsPerClient);
            }
            roomsPerClient = Integer.parseInt(value);
        }

        boolean ackExtension = this.ackExtension;
        if (interactive) {
            System.err.printf("enable ack extension [%b]: ", ackExtension);
            String value = console.readLine().trim();
            if (value.length() == 0) {
                value = String.valueOf(ackExtension);
            }
            ackExtension = Boolean.parseBoolean(value);
        }

        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        mbeanContainer.beanAdded(null, this);

        threadPool.setMaxThreads(maxThreads);
        threadPool.setDaemon(true);
        threadPool.start();
        mbeanContainer.beanAdded(null, threadPool);

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setExecutor(threadPool);
        clientConnector.setSelectors(selectors);
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
        HttpClientTransport httpClientTransport = new HttpClientTransportOverHTTP(clientConnector);
        if (http2) {
            HTTP2Client http2Client = new HTTP2Client(clientConnector);
            httpClientTransport = new HttpClientTransportOverHTTP2(http2Client);
        }
        httpClient = new HttpClient(httpClientTransport);
        httpClient.setMaxConnectionsPerDestination(60000);
        httpClient.setMaxRequestsQueuedPerDestination(10000);
        httpClient.setIdleTimeout(Config.META_CONNECT_TIMEOUT + 2 * Config.MAX_NETWORK_DELAY);
        httpClient.setSocketAddressResolver(new SocketAddressResolver.Sync());
        httpClient.addBean(mbeanContainer);
        httpClient.addBean(connectionStatistics);
        LifeCycle.start(httpClient);
        mbeanContainer.beanAdded(null, httpClient);

        webSocketClient = new WebSocketClient(httpClient);
        webSocketClient.setInputBufferSize(8 * 1024);
        webSocketClient.setMaxTextMessageSize(Integer.MAX_VALUE);
        webSocketClient.addBean(mbeanContainer);
        webSocketClient.addBean(connectionStatistics);
        LifeCycle.start(webSocketClient);
        mbeanContainer.beanAdded(null, webSocketClient);

        webSocketContainer = JakartaWebSocketClientContainerProvider.getContainer(httpClient);
        Container.addBean(webSocketContainer, mbeanContainer);
        Container.addBean(webSocketContainer, connectionStatistics);
        mbeanContainer.beanAdded(null, webSocketContainer);

        latencyListener = new LatencyListener();
        handshakeListener = new HandshakeListener(channel, rooms, roomsPerClient);
        disconnectListener = new DisconnectListener();

        LoadBayeuxClient statsClient = new LoadBayeuxClient(url, scheduler, newClientTransport(transport));
        statsClient.handshake();

        int clients = this.clients;
        int batches = this.batches;
        int batchSize = this.batchSize;
        long batchPause = this.batchPause;
        int messageSize = this.messageSize;
        boolean randomize = this.randomize;

        while (true) {
            System.err.println();
            System.err.println("-----");

            if (interactive) {
                System.err.printf("clients [%d]: ", clients);
                String value = console.readLine();
                if (value == null) {
                    break;
                }
                value = value.trim();
                if (value.length() == 0) {
                    value = String.valueOf(clients);
                }
                clients = Integer.parseInt(value);
            } else if (iterations-- == 0) {
                clients = 0;
            }

            System.err.println("Waiting for clients to be ready...");

            // Create or remove the necessary bayeux clients
            int currentClients = bayeuxClients.size();
            if (currentClients < clients) {
                for (int i = 0; i < clients - currentClients; ++i) {
                    bayeuxClients.add(handshakeClient(url, transport, ackExtension));
                }
            } else if (currentClients > clients) {
                for (int i = 0; i < currentClients - clients; ++i) {
                    disconnectClient(bayeuxClients.remove(currentClients - i - 1));
                }
            }

            int currentSize = bayeuxClients.size();
            if (currentSize == 0) {
                System.err.println("All clients disconnected, exiting");
                break;
            }
            System.err.printf("Clients ready: %d%n", currentSize);

            reset();

            if (interactive) {
                System.err.printf("batch count [%d]: ", batches);
                String value = console.readLine().trim();
                if (value.length() == 0) {
                    value = String.valueOf(batches);
                }
                batches = Integer.parseInt(value);
            }

            if (interactive) {
                System.err.printf("batch size [%d]: ", batchSize);
                String value = console.readLine().trim();
                if (value.length() == 0) {
                    value = String.valueOf(batchSize);
                }
                batchSize = Integer.parseInt(value);
            }

            if (interactive) {
                System.err.printf("batch pause (\u00B5s) [%d]: ", batchPause);
                String value = console.readLine().trim();
                if (value.length() == 0) {
                    value = String.valueOf(batchPause);
                }
                batchPause = Long.parseLong(value);
            }

            if (interactive) {
                System.err.printf("message size [%d]: ", messageSize);
                String value = console.readLine().trim();
                if (value.length() == 0) {
                    value = String.valueOf(messageSize);
                }
                messageSize = Integer.parseInt(value);
            }
            char[] chars = new char[messageSize];
            Arrays.fill(chars, 'x');
            String chat = new String(chars);

            if (interactive) {
                System.err.printf("randomize sends [%b]: ", randomize);
                String value = console.readLine().trim();
                if (value.length() == 0) {
                    value = String.valueOf(randomize);
                }
                randomize = Boolean.parseBoolean(value);
            }

            // Send a message to the server to signal the start of the test.
            statsClient.begin();

            PlatformMonitor.Start start = monitor.start();
            System.err.println();
            System.err.println(start);
            System.err.printf("Testing %d clients in %d rooms, %d rooms/client%n", bayeuxClients.size(), rooms, roomsPerClient);
            System.err.printf("Sending %d batches of %dx%d bytes messages every %d \u00B5s%n", batches, batchSize, messageSize, batchPause);

            long begin = System.nanoTime();
            long expected = runBatches(channel, batches, batchSize, TimeUnit.MICROSECONDS.toNanos(batchPause), chat, randomize);
            long end = System.nanoTime();
            long sendElapsed = end - begin;

            PlatformMonitor.Stop stop = monitor.stop();
            System.err.println(stop);

            waitForMessages(expected);

            long messages = this.messages.get();
            long receiveElapsed = this.end.get() - this.start.get();

            // Send a message to the server to signal the end of the test.
            statsClient.end();

            Histogram histogram = printResults(messages, expected, sendElapsed, receiveElapsed);
            if (!interactive) {
                Map<String, Object> run = new LinkedHashMap<>();
                Map<String, Object> config = new LinkedHashMap<>();
                run.put("config", config);
                config.put("cores", start.cores);
                config.put("totalMemory", new Measure(start.gibiBytes(start.totalMemory), "GiB"));
                config.put("os", start.os);
                config.put("jvm", start.jvm);
                config.put("totalHeap", new Measure(start.gibiBytes(start.heap.getMax()), "GiB"));
                config.put("date", new Date(start.date).toString());
                config.put("transport", transport.getName());
                config.put("clients", bayeuxClients.size());
                config.put("rooms", rooms);
                config.put("roomsPerClient", roomsPerClient);
                config.put("batches", batches);
                config.put("batchSize", batchSize);
                config.put("batchPause", new Measure(batchPause, "\u00B5s"));
                config.put("messageSize", new Measure(messageSize, "B"));
                Map<String, Object> results = new LinkedHashMap<>();
                run.put("results", results);
                results.put("cpu", new Measure(stop.percent(stop.cpuTime, stop.time) / start.cores, "%"));
                results.put("jitTime", new Measure(stop.jitTime, "ms"));
                results.put("messages", messages);
                results.put("sendTime", new Measure(TimeUnit.NANOSECONDS.toMillis(sendElapsed), "ms"));
                results.put("sendRate", new Measure(messages * 1000L * 1000 * 1000 / sendElapsed, "messages/s"));
                results.put("receiveTime", new Measure(TimeUnit.NANOSECONDS.toMillis(receiveElapsed), "ms"));
                results.put("receiveRate", new Measure(messages * 1000L * 1000 * 1000 / receiveElapsed, "messages/s"));
                Map<String, Object> latency = new LinkedHashMap<>();
                results.put("latency", latency);
                latency.put("min", new Measure(convert(histogram.getMinValue()), "\u00B5s"));
                latency.put("p50", new Measure(convert(histogram.getValueAtPercentile(50D)), "\u00B5s"));
                latency.put("p99", new Measure(convert(histogram.getValueAtPercentile(99D)), "\u00B5s"));
                latency.put("max", new Measure(convert(histogram.getMaxValue()), "\u00B5s"));
                Map<String, Object> threadPool = new LinkedHashMap<>();
                results.put("threadPool", threadPool);
                threadPool.put("tasks", this.threadPool.getTasks());
                threadPool.put("queueSizeMax", this.threadPool.getMaxQueueSize());
                threadPool.put("activeThreadsMax", this.threadPool.getMaxActiveThreads());
                threadPool.put("queueLatencyAverage", new Measure(TimeUnit.NANOSECONDS.toMillis(this.threadPool.getAverageQueueLatency()), "ms"));
                threadPool.put("queueLatencyMax", new Measure(TimeUnit.NANOSECONDS.toMillis(this.threadPool.getMaxQueueLatency()), "ms"));
                threadPool.put("taskTimeAverage", new Measure(TimeUnit.NANOSECONDS.toMillis(this.threadPool.getAverageTaskLatency()), "ms"));
                threadPool.put("taskTimeMax", new Measure(TimeUnit.NANOSECONDS.toMillis(this.threadPool.getMaxTaskLatency()), "ms"));
                Map<String, Object> gc = new LinkedHashMap<>();
                results.put("gc", gc);
                gc.put("youngCount", stop.youngCount);
                gc.put("youngTime", new Measure(stop.youngTime, "ms"));
                gc.put("oldCount", stop.oldCount);
                gc.put("oldTime", new Measure(stop.oldTime, "ms"));
                gc.put("youngGarbage", new Measure(stop.mebiBytes(stop.edenBytes + stop.survivorBytes), "MiB"));
                gc.put("oldGarbage", new Measure(stop.mebiBytes(stop.tenuredBytes), "MiB"));
                saveResults(run, file);
            }
        }

        statsClient.exit();

        LifeCycle.stop(webSocketContainer);
        LifeCycle.stop(webSocketClient);
        LifeCycle.stop(httpClient);

        scheduler.shutdown();
    }

    private long runBatches(String channel, int batchCount, int batchSize, long batchPauseNanos, String chat, boolean randomize) {
        long begin = System.nanoTime();
        int index = -1;
        long expected = 0;
        for (int i = 1; i <= batchCount; ++i) {
            long pause = begin + i * batchPauseNanos - System.nanoTime();
            if (pause > 0) {
                nanoSleep(pause);
            }

            if (randomize) {
                index = nextRandom(bayeuxClients.size());
            } else {
                ++index;
                if (index == bayeuxClients.size()) {
                    index = 0;
                }
            }
            LoadBayeuxClient client = bayeuxClients.get(index);
            expected += sendBatch(client, channel, batchSize, chat);
        }
        return expected;
    }

    protected LoadBayeuxClient handshakeClient(String url, ClientTransportType transport, boolean ackExtension) {
        LoadBayeuxClient client = new LoadBayeuxClient(url, scheduler, newClientTransport(transport));
        if (ackExtension) {
            client.addExtension(new AckExtension());
        }
        client.getChannel(Channel.META_HANDSHAKE).addListener(handshakeListener);
        client.getChannel(Channel.META_DISCONNECT).addListener(disconnectListener);
        client.handshake();
        client.waitForInit();
        return client;
    }

    protected void disconnectClient(LoadBayeuxClient client) {
        client.disconnect();
        client.waitFor(1000, BayeuxClient.State.DISCONNECTED);
    }

    private void nanoSleep(long pause) {
        try {
            TimeUnit.NANOSECONDS.sleep(pause);
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(x);
        }
    }

    private long sendBatch(LoadBayeuxClient client, String channel, int batchSize, String chat) {
        long expected = 0;
        List<Integer> rooms = new ArrayList<>(roomMap.keySet());
        client.startBatch();
        for (int b = 0; b < batchSize; ++b) {
            int room = -1;
            AtomicInteger clientsPerRoom = null;
            while (clientsPerRoom == null || clientsPerRoom.get() == 0) {
                room = rooms.get(nextRandom(rooms.size()));
                clientsPerRoom = roomMap.get(room);
            }
            Map<String, Object> message = new HashMap<>(5);
            // Additional fields to simulate a chat message
            message.put("room", room);
            message.put("user", client.hashCode());
            message.put("chat", chat);
            // Mandatory fields to record latencies
            message.put(START_FIELD, System.nanoTime());
            message.put(Config.ID_FIELD, ids.incrementAndGet() + channel);
            ClientSessionChannel clientChannel = client.getChannel(getChannelId(channel + "/" + room));
            clientChannel.publish(message);
            clientChannel.release();
            expected += clientsPerRoom.get();
        }
        client.endBatch();
        return expected;
    }

    private ClientTransport newClientTransport(ClientTransportType clientTransportType) {
        switch (clientTransportType) {
            case LONG_POLLING: {
                Map<String, Object> options = new HashMap<>();
                options.put(ClientTransport.JSON_CONTEXT_OPTION, new JacksonJSONContextClient());
                options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, Config.MAX_NETWORK_DELAY);
                options.put(ClientTransport.MAX_MESSAGE_SIZE_OPTION, Integer.MAX_VALUE);
                return new JettyHttpClientTransport(options, httpClient) {
                    @Override
                    protected void customize(Request request) {
                        super.customize(request);
                        if (request.getPath().endsWith("/disconnect")) {
                            request.headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE));
                        }
                    }
                };
            }
            case JAKARTA_WEBSOCKET: {
                Map<String, Object> options = new HashMap<>();
                options.put(ClientTransport.JSON_CONTEXT_OPTION, new JacksonJSONContextClient());
                options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, Config.MAX_NETWORK_DELAY);
                options.put(ClientTransport.MAX_MESSAGE_SIZE_OPTION, Integer.MAX_VALUE);
                // Differently from HTTP where the idle timeout is adjusted if it is a /meta/connect
                // for WebSocket we need an idle timeout that is longer than the /meta/connect timeout.
                options.put(WebSocketTransport.IDLE_TIMEOUT_OPTION, Config.META_CONNECT_TIMEOUT + httpClient.getIdleTimeout());
                options.put(WebSocketTransport.PERMESSAGE_DEFLATE_OPTION, perMessageDeflate);
                return new WebSocketTransport(options, scheduler, webSocketContainer);
            }
            case JETTY_WEBSOCKET: {
                Map<String, Object> options = new HashMap<>();
                options.put(ClientTransport.JSON_CONTEXT_OPTION, new JacksonJSONContextClient());
                options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, Config.MAX_NETWORK_DELAY);
                options.put(ClientTransport.MAX_MESSAGE_SIZE_OPTION, Integer.MAX_VALUE);
                // Differently from HTTP where the idle timeout is adjusted if it is a /meta/connect
                // for WebSocket we need an idle timeout that is longer than the /meta/connect timeout.
                options.put(JettyWebSocketTransport.IDLE_TIMEOUT_OPTION, Config.META_CONNECT_TIMEOUT + httpClient.getIdleTimeout());
                options.put(WebSocketTransport.PERMESSAGE_DEFLATE_OPTION, perMessageDeflate);
                return new JettyWebSocketTransport(options, scheduler, webSocketClient);
            }
            default: {
                throw new IllegalArgumentException();
            }
        }
    }

    private int nextRandom(int limit) {
        return ThreadLocalRandom.current().nextInt(limit);
    }

    private void updateLatencies(long startTime, long sendTime, long arrivalTime, long endTime) {
        long wallLatency = endTime - startTime;
        histogram.get().recordValue(wallLatency);

        long latency = TimeUnit.MICROSECONDS.toNanos(TimeUnit.NANOSECONDS.toMicros(arrivalTime - sendTime));
        Atomics.updateMin(minLatency, latency);
        Atomics.updateMax(maxLatency, latency);
        totLatency.addAndGet(latency);
    }

    private void waitForMessages(long expected) throws InterruptedException {
        long arrived = messages.get();
        long lastArrived = 0;
        int maxRetries = 20;
        int retries = maxRetries;
        while (arrived < expected) {
            System.err.printf("Waiting for messages to arrive %d/%d%n", arrived, expected);
            Thread.sleep(500);
            if (lastArrived == arrived) {
                --retries;
                if (retries == 0) {
                    break;
                }
            } else {
                lastArrived = arrived;
                retries = maxRetries;
            }
            arrived = messages.get();
        }
        if (arrived < expected) {
            System.err.printf("Interrupting wait for messages %d/%d%n", arrived, expected);
        } else {
            System.err.printf("All messages arrived %d/%d%n", arrived, expected);
        }
    }

    private Histogram printResults(long messageCount, long expectedCount, long sendElapsed, long receiveElapsed) {
        System.err.printf("Messages - Success/Expected = %d/%d%n", messageCount, expectedCount);

        DynamicConnectionStatistics.Data data = connectionStatistics.collect();

        if (sendElapsed > 0) {
            long batchRate = batches * 1000L * 1000 * 1000 / sendElapsed;
            float uploadRate = data.sentBytes * 1000F * 1000 * 1000 / sendElapsed / 1024 / 1024;
            System.err.printf("Outgoing: Elapsed = %d ms | Rate = %d messages/s - %d batches/s - %.3f MiB/s%n",
                    TimeUnit.NANOSECONDS.toMillis(sendElapsed),
                    batchSize * batchRate,
                    batchRate,
                    uploadRate
            );
        }

        if (receiveElapsed > 0) {
            float downloadRate = data.receivedBytes * 1000F * 1000 * 1000 / receiveElapsed / 1024 / 1024;
            System.err.printf("Incoming - Elapsed = %d ms | Rate = %d messages/s - %d batches/s(%.2f%%) - %.3f MiB/s%n",
                    TimeUnit.NANOSECONDS.toMillis(receiveElapsed),
                    messageCount * 1000L * 1000 * 1000 / receiveElapsed,
                    responses.get() * 1000L * 1000 * 1000 / receiveElapsed,
                    100F * responses.get() / messageCount,
                    downloadRate
            );
        }

        Histogram histogram = allHistograms.stream().reduce(new Histogram(TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MINUTES.toNanos(1), 3), (h1, h2) -> {
            h1.add(h2);
            return h1;
        });
        System.err.println(new HistogramSnapshot(histogram, 20, "Messages - Latency", "\u00B5s", this));

        System.err.printf("Messages - Network Latency Min/Ave/Max = %d/%d/%d ms%n",
                TimeUnit.NANOSECONDS.toMillis(minLatency.get()),
                messageCount == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(totLatency.get() / messageCount),
                TimeUnit.NANOSECONDS.toMillis(maxLatency.get()));

        System.err.printf("Slowest Message ID = %s time = %d ms%n", maxTime.getReference(), maxTime.getStamp());

        Config.printThreadPool("Thread Pool", threadPool);

        return histogram;
    }

    private void saveResults(Map<String, Object> run, String path) {
        try {
            File file = new File(path);
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(file, run);
            System.err.printf("Results saved to file %s%n", file.getAbsolutePath());
        } catch (IOException x) {
            System.err.printf("Could not save results to file %s%n", path);
        }
    }

    @Override
    public long convert(long measure) {
        return TimeUnit.NANOSECONDS.toMicros(measure);
    }

    private void reset() {
        allHistograms.forEach(Histogram::reset);
        threadPool.reset();
        start.set(0L);
        end.set(0L);
        responses.set(0L);
        messages.set(0L);
        minLatency.set(Long.MAX_VALUE);
        maxLatency.set(0L);
        totLatency.set(0L);
        maxTime.set(null, 0);
        sendTimes.clear();
        arrivalTimes.clear();
        connectionStatistics.reset();
    }

    private class HandshakeListener implements ClientSessionChannel.MessageListener {
        private static final String SESSION_ID_ATTRIBUTE = "session_id";
        private final String channel;
        private final int rooms;
        private final int roomsPerClient;

        private HandshakeListener(String channel, int rooms, int roomsPerClient) {
            this.channel = channel;
            this.rooms = rooms;
            this.roomsPerClient = roomsPerClient;
        }

        @Override
        public void onMessage(ClientSessionChannel handshakeChannel, Message handshakeReply) {
            if (handshakeReply.isSuccessful()) {
                LoadBayeuxClient client = (LoadBayeuxClient)handshakeChannel.getSession();
                String sessionId = (String)client.getAttribute(SESSION_ID_ATTRIBUTE);
                if (sessionId == null) {
                    client.setAttribute(SESSION_ID_ATTRIBUTE, client.getId());
                    client.batch(() -> {
                        List<Integer> roomsSubscribedTo = new ArrayList<>();
                        for (int j = 0; j < roomsPerClient; ++j) {
                            // Avoid to subscribe the same client twice to the same room
                            int room = nextRandom(rooms);
                            while (roomsSubscribedTo.contains(room)) {
                                room = nextRandom(rooms);
                            }
                            roomsSubscribedTo.add(room);
                            client.setupRoom(room);
                            client.getChannel(channel + "/" + room).subscribe(latencyListener);
                        }
                        client.init();
                    });
                } else {
                    System.err.printf("Second handshake for client %s: old session %s, new session %s%n", this, sessionId, client.getId());
                }
            }
        }
    }

    private class DisconnectListener implements ClientSessionChannel.MessageListener {
        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            if (message.isSuccessful()) {
                LoadBayeuxClient client = (LoadBayeuxClient)channel.getSession();
                client.destroy();
            }
        }
    }

    private class LatencyListener implements ClientSessionChannel.MessageListener {
        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            Map<String, Object> data = message.getDataAsMap();
            if (data != null) {
                long startTime = ((Number)data.get(START_FIELD)).longValue();
                long endTime = System.nanoTime();
                start.compareAndSet(0, endTime);
                end.set(endTime);
                messages.incrementAndGet();

                String id = (String)data.get(Config.ID_FIELD);

                AtomicStampedReference<Long> sendTimeRef = sendTimes.get(id);
                long sendTime = sendTimeRef.getReference();
                // Update count atomically
                if (Atomics.decrement(sendTimeRef) == 0) {
                    sendTimes.remove(id);
                }

                AtomicStampedReference<List<Long>> arrivalTimeRef = arrivalTimes.get(id);
                long arrivalTime = arrivalTimeRef.getReference().remove(0);
                // Update count atomically
                if (Atomics.decrement(arrivalTimeRef) == 0) {
                    arrivalTimes.remove(id);
                }

                long delayMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                Atomics.updateMax(maxTime, id, (int)delayMs);

                updateLatencies(startTime, sendTime, arrivalTime, endTime);
            } else {
                throw new IllegalStateException("No 'data' field in message " + message);
            }
        }
    }

    private class LoadBayeuxClient extends BayeuxClient {
        private final List<Integer> subscriptions = new ArrayList<>();
        private final CountDownLatch initLatch = new CountDownLatch(1);

        private LoadBayeuxClient(String url, ScheduledExecutorService scheduler, ClientTransport transport) {
            super(url, scheduler, transport);
            addTransportListener(new TransportListener() {
                @Override
                public void onSending(List<? extends Message> messages) {
                    recordSentMessages(messages);
                }

                @Override
                public void onMessages(List<Message.Mutable> messages) {
                    recordReceivedMessages(messages);
                }
            });
        }

        public void setupRoom(int room) {
            AtomicInteger clientsPerRoom = roomMap.get(room);
            if (clientsPerRoom == null) {
                clientsPerRoom = new AtomicInteger();
                AtomicInteger existing = roomMap.putIfAbsent(room, clientsPerRoom);
                if (existing != null) {
                    clientsPerRoom = existing;
                }
            }
            clientsPerRoom.incrementAndGet();
            subscriptions.add(room);
        }

        public void init() {
            getChannel("/service/init").publish(new HashMap<>(), message -> initLatch.countDown());
        }

        public void waitForInit() {
            try {
                initLatch.await();
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(x);
            }
        }

        public void destroy() {
            for (Integer room : subscriptions) {
                AtomicInteger clientsPerRoom = roomMap.get(room);
                clientsPerRoom.decrementAndGet();
            }
            subscriptions.clear();
        }

        public void begin() throws InterruptedException {
            notifyServer("/service/statistics/start");
        }

        public void end() throws InterruptedException {
            notifyServer("/service/statistics/stop");
        }

        public void exit() throws InterruptedException {
            notifyServer("/service/statistics/exit");
        }

        private void notifyServer(String channelName) throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            ClientSessionChannel channel = getChannel(channelName);
            channel.publish(new HashMap<String, Object>(1), message -> latch.countDown());
            latch.await();
        }

        private void recordSentMessages(List<? extends Message> messages) {
            long now = System.nanoTime();
            for (Message message : messages) {
                Map<String, Object> data = message.getDataAsMap();
                if (data != null && message.getChannelId().isBroadcast()) {
                    int room = (Integer)data.get("room");
                    int clientsInRoom = roomMap.get(room).get();
                    String id = (String)data.get(Config.ID_FIELD);
                    sendTimes.put(id, new AtomicStampedReference<>(now, clientsInRoom));
                    arrivalTimes.put(id, new AtomicStampedReference<>(new BlockingArrayQueue<>(), clientsInRoom));
                }
            }
        }

        private void recordReceivedMessages(List<Message.Mutable> messages) {
            long now = System.nanoTime();
            boolean response = false;
            for (Message message : messages) {
                Map<String, Object> data = message.getDataAsMap();
                if (data != null) {
                    response = true;
                    String id = (String)data.get(Config.ID_FIELD);
                    arrivalTimes.get(id).getReference().add(now);
                }
            }
            if (response) {
                responses.incrementAndGet();
            }
        }
    }

    private ChannelId getChannelId(String channelName) {
        ChannelId result = channelIds.get(channelName);
        if (result == null) {
            result = new ChannelId(channelName);
            ChannelId existing = channelIds.putIfAbsent(channelName, result);
            if (existing != null) {
                result = existing;
            }
        }
        return result;
    }

    private enum ClientTransportType {
        LONG_POLLING("long-polling"), JAKARTA_WEBSOCKET("jakarta-websocket"), JETTY_WEBSOCKET("jetty-websocket");

        private final String name;

        private ClientTransportType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    private static class Measure extends HashMap<String, Object> {
        public Measure(Object value, String unit) {
            super(2);
            put("value", value);
            put("unit", unit);
        }
    }

    private static class DynamicConnectionStatistics implements Connection.Listener {
        private final Set<Connection> _connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final LongAdder _rcvdBytes = new LongAdder();
        private final LongAdder _sentBytes = new LongAdder();
        private Data _lastData = new Data(0, 0);

        @Override
        public void onOpened(Connection connection) {
            _connections.add(connection);
        }

        @Override
        public void onClosed(Connection connection) {
            _connections.remove(connection);
            collect(connection);
        }

        public void reset() {
            _lastData = new Data(_rcvdBytes.sumThenReset(), _sentBytes.sumThenReset());
        }

        public Data collect() {
            _connections.forEach(this::collect);
            return new Data(_rcvdBytes.longValue() - _lastData.receivedBytes,
                    _sentBytes.longValue() - _lastData.sentBytes);
        }

        private void collect(Connection connection) {
            long bytesIn = connection.getBytesIn();
            if (bytesIn > 0) {
                _rcvdBytes.add(bytesIn);
            }
            long bytesOut = connection.getBytesOut();
            if (bytesOut > 0) {
                _sentBytes.add(bytesOut);
            }
        }

        public static class Data {
            public final long receivedBytes;
            public final long sentBytes;

            private Data(long receivedBytes, long sentBytes) {
                this.receivedBytes = receivedBytes;
                this.sentBytes = sentBytes;
            }
        }
    }
}
