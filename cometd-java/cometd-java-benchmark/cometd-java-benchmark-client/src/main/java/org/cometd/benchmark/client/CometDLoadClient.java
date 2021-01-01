/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicStampedReference;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.HdrHistogram.AtomicHistogram;
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
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.JacksonJSONContextClient;
import org.cometd.websocket.client.JettyWebSocketTransport;
import org.cometd.websocket.client.WebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.toolchain.perf.HistogramSnapshot;
import org.eclipse.jetty.toolchain.perf.MeasureConverter;
import org.eclipse.jetty.toolchain.perf.PlatformMonitor;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.client.WebSocketClient;

public class CometDLoadClient implements MeasureConverter {
    private static final String START_FIELD = "start";

    private final AtomicHistogram histogram = new AtomicHistogram(TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MINUTES.toNanos(1), 3);
    private final Random random = new Random();
    private final PlatformMonitor monitor = new PlatformMonitor();
    private final AtomicLong ids = new AtomicLong();
    private final List<LoadBayeuxClient> bayeuxClients = Collections.synchronizedList(new ArrayList<LoadBayeuxClient>());
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
    private final WebSocketContainer webSocketContainer = ContainerProvider.getWebSocketContainer();
    private final WebSocketClient webSocketClient = new WebSocketClient(new SslContextFactory(true));
    private HttpClient httpClient;
    private LatencyListener latencyListener;
    private HandshakeListener handshakeListener;
    private DisconnectListener disconnectListener;
    private boolean interactive = true;
    private String host = "localhost";
    private int port = 8080;
    private ClientTransportType transport = ClientTransportType.LONG_POLLING;
    private boolean tls = false;
    private int selectors = 1;
    private int maxThreads = 256;
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
            } else if (arg.startsWith("--transport=")) {
                client.transport = ClientTransportType.valueOf(arg.substring("--transport=".length()));
            } else if (arg.equals("--tls")) {
                client.tls = true;
            } else if (arg.startsWith("--selectors=")) {
                client.selectors = Integer.parseInt(arg.substring("--selectors=".length()));
            } else if (arg.startsWith("--maxThreads=")) {
                client.maxThreads = Integer.parseInt(arg.substring("--maxThreads=".length()));
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

        httpClient = new HttpClient(new HttpClientTransportOverHTTP(selectors), new SslContextFactory(true));
        httpClient.addBean(mbeanContainer);
        httpClient.setMaxConnectionsPerDestination(60000);
        httpClient.setMaxRequestsQueuedPerDestination(10000);
        httpClient.setExecutor(threadPool);
        httpClient.setIdleTimeout(2 * Config.MAX_NETWORK_DELAY);
        httpClient.setSocketAddressResolver(new SocketAddressResolver.Sync());
        httpClient.start();
        mbeanContainer.beanAdded(null, httpClient);

        // Make sure the container is stopped when the HttpClient is stopped
        httpClient.addBean(webSocketContainer, true);
        mbeanContainer.beanAdded(null, webSocketContainer);

        webSocketClient.setExecutor(threadPool);
        webSocketClient.getPolicy().setInputBufferSize(8 * 1024);
        webSocketClient.addBean(mbeanContainer);
        webSocketClient.start();
        mbeanContainer.beanAdded(null, webSocketClient);

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

            PlatformMonitor.Stop stop = monitor.stop();
            System.err.println(stop);

            long sendElapsed = end - begin;
            long sendTime = TimeUnit.NANOSECONDS.toMillis(sendElapsed);
            long sendRate = 0;
            if (sendElapsed > 0) {
                sendRate = batches * batchSize * 1000L * 1000 * 1000 / sendElapsed;
                System.err.printf("Outgoing: Elapsed = %d ms | Rate = %d messages/s - %d requests/s - ~%.3f Mib/s%n",
                        sendTime,
                        sendRate,
                        batches * 1000L * 1000 * 1000 / sendElapsed,
                        batches * batchSize * messageSize * 8F * 1000 * 1000 * 1000 / sendElapsed / 1024 / 1024
                );
            }

            waitForMessages(expected);
            long messages = this.messages.get();

            long receiveElapsed = this.end.get() - this.start.get();
            long receiveRate = 0;
            if (receiveElapsed > 0) {
                receiveRate = messages * 1000 * 1000 * 1000 / receiveElapsed;
            }

            // Send a message to the server to signal the end of the test.
            statsClient.end();

            Histogram histogram = printResults(messages, expected, receiveElapsed, messageSize);
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
                results.put("sendRate", new Measure(sendRate, "messages/s"));
                results.put("receiveTime", new Measure(TimeUnit.NANOSECONDS.toMillis(receiveElapsed), "ms"));
                results.put("receiveRate", new Measure(receiveRate, "messages/s"));
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

            reset();
        }

        statsClient.exit();

        webSocketClient.stop();

        httpClient.stop();

        threadPool.stop();

        scheduler.shutdown();
        scheduler.awaitTermination(1000, TimeUnit.MILLISECONDS);
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
                return new LongPollingTransport(options, httpClient) {
                    @Override
                    protected void customize(Request request) {
                        super.customize(request);
                        if (request.getPath().endsWith("/disconnect")) {
                            request.header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
                        }
                    }
                };
            }
            case JSR_WEBSOCKET: {
                Map<String, Object> options = new HashMap<>();
                options.put(ClientTransport.JSON_CONTEXT_OPTION, new JacksonJSONContextClient());
                options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, Config.MAX_NETWORK_DELAY);
                // Differently from HTTP where the idle timeout is adjusted if it is a /meta/connect
                // for WebSocket we need an idle timeout that is longer than the /meta/connect timeout.
                options.put(WebSocketTransport.IDLE_TIMEOUT_OPTION, Config.META_CONNECT_TIMEOUT + httpClient.getIdleTimeout());
                return new WebSocketTransport(options, scheduler, webSocketContainer);
            }
            case JETTY_WEBSOCKET: {
                Map<String, Object> options = new HashMap<>();
                options.put(ClientTransport.JSON_CONTEXT_OPTION, new JacksonJSONContextClient());
                options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, Config.MAX_NETWORK_DELAY);
                // Differently from HTTP where the idle timeout is adjusted if it is a /meta/connect
                // for WebSocket we need an idle timeout that is longer than the /meta/connect timeout.
                options.put(JettyWebSocketTransport.IDLE_TIMEOUT_OPTION, Config.META_CONNECT_TIMEOUT + httpClient.getIdleTimeout());
                return new JettyWebSocketTransport(options, scheduler, webSocketClient);
            }
            default: {
                throw new IllegalArgumentException();
            }
        }
    }

    private int nextRandom(int limit) {
        synchronized (this) {
            return random.nextInt(limit);
        }
    }

    private void updateLatencies(long startTime, long sendTime, long arrivalTime, long endTime) {
        long wallLatency = endTime - startTime;
        histogram.recordValue(wallLatency);

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

    private Histogram printResults(long messageCount, long expectedCount, long elapsedNanos, int messageSize) {
        System.err.printf("Messages - Success/Expected = %d/%d%n", messageCount, expectedCount);

        if (elapsedNanos > 0) {
            System.err.printf("Incoming - Elapsed = %d ms | Rate = %d messages/s - %d responses/s(%.2f%%) - ~%.3f Mib/s%n",
                    TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                    messageCount * 1000L * 1000 * 1000 / elapsedNanos,
                    responses.get() * 1000L * 1000 * 1000 / elapsedNanos,
                    100F * responses.get() / messageCount,
                    messageCount * messageSize * 8F * 1000 * 1000 * 1000 / elapsedNanos / 1024 / 1024
            );
        }

        AtomicHistogram histogram = this.histogram.copy();
        System.err.println(new HistogramSnapshot(histogram, 20, "Messages - Latency", "\u00B5s", this));

        System.err.printf("Messages - Network Latency Min/Ave/Max = %d/%d/%d ms%n",
                TimeUnit.NANOSECONDS.toMillis(minLatency.get()),
                messageCount == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(totLatency.get() / messageCount),
                TimeUnit.NANOSECONDS.toMillis(maxLatency.get()));

        System.err.printf("Slowest Message ID = %s time = %d ms%n", maxTime.getReference(), maxTime.getStamp());

        System.err.printf("Thread Pool - Tasks = %d | Concurrent Threads max = %d | Queue Size max = %d | Queue Latency avg/max = %d/%d ms | Task Latency avg/max = %d/%d ms%n",
                threadPool.getTasks(),
                threadPool.getMaxActiveThreads(),
                threadPool.getMaxQueueSize(),
                TimeUnit.NANOSECONDS.toMillis(threadPool.getAverageQueueLatency()),
                TimeUnit.NANOSECONDS.toMillis(threadPool.getMaxQueueLatency()),
                TimeUnit.NANOSECONDS.toMillis(threadPool.getAverageTaskLatency()),
                TimeUnit.NANOSECONDS.toMillis(threadPool.getMaxTaskLatency()));

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
        histogram.reset();
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
        public void onMessage(final ClientSessionChannel handshakeChannel, Message handshakeReply) {
            if (handshakeReply.isSuccessful()) {
                final LoadBayeuxClient client = (LoadBayeuxClient)handshakeChannel.getSession();
                String sessionId = (String)client.getAttribute(SESSION_ID_ATTRIBUTE);
                if (sessionId == null) {
                    client.setAttribute(SESSION_ID_ATTRIBUTE, client.getId());
                    client.batch(new Runnable() {
                        @Override
                        public void run() {
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
                        }
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
            getChannel("/service/init").publish(new HashMap<>(), new ClientSessionChannel.MessageListener() {
                @Override
                public void onMessage(ClientSessionChannel channel, Message message) {
                    initLatch.countDown();
                }
            });
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
            final CountDownLatch latch = new CountDownLatch(1);
            ClientSessionChannel channel = getChannel(channelName);
            channel.publish(new HashMap<String, Object>(1), new ClientSessionChannel.MessageListener() {
                @Override
                public void onMessage(ClientSessionChannel channel, Message message) {
                    latch.countDown();
                }
            });
            latch.await();
        }

        @Override
        public void onSending(List<? extends Message> messages) {
            long now = System.nanoTime();
            for (Message message : messages) {
                Map<String, Object> data = message.getDataAsMap();
                if (data != null && message.getChannelId().isBroadcast()) {
                    int room = (Integer)data.get("room");
                    int clientsInRoom = roomMap.get(room).get();
                    String id = (String)data.get(Config.ID_FIELD);
                    sendTimes.put(id, new AtomicStampedReference<>(now, clientsInRoom));
                    // There is no write-cheap concurrent list in JDK, so let's use a synchronized wrapper
                    arrivalTimes.put(id, new AtomicStampedReference<>(Collections.synchronizedList(new LinkedList<Long>()), clientsInRoom));
                }
            }
        }

        @Override
        public void onMessages(List<Message.Mutable> messages) {
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
        LONG_POLLING("long-polling"), JSR_WEBSOCKET("jsr-websocket"), JETTY_WEBSOCKET("jetty-websocket");

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
}
