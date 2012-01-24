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
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicStampedReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.JacksonJSONContextClient;
import org.cometd.websocket.client.WebSocketTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.websocket.WebSocketClientFactory;
import org.eclipse.jetty.websocket.ZeroMaskGen;

public class BayeuxLoadClient
{
    private final Random random = new Random();
    private final BenchmarkHelper helper = new BenchmarkHelper();
    private final List<LoadBayeuxClient> bayeuxClients = Collections.synchronizedList(new ArrayList<LoadBayeuxClient>());
    private final ConcurrentMap<Integer, AtomicInteger> rooms = new ConcurrentHashMap<Integer, AtomicInteger>();
    private final AtomicLong start = new AtomicLong();
    private final AtomicLong end = new AtomicLong();
    private final AtomicLong responses = new AtomicLong();
    private final AtomicLong messages = new AtomicLong();
    private final AtomicLong minWallLatency = new AtomicLong();
    private final AtomicLong maxWallLatency = new AtomicLong();
    private final AtomicLong totWallLatency = new AtomicLong();
    private final AtomicLong minLatency = new AtomicLong();
    private final AtomicLong maxLatency = new AtomicLong();
    private final AtomicLong totLatency = new AtomicLong();
    private final ConcurrentMap<Long, AtomicLong> wallLatencies = new ConcurrentHashMap<Long, AtomicLong>();
    private final Map<String, AtomicStampedReference<Long>> sendTimes = new ConcurrentHashMap<String, AtomicStampedReference<Long>>();
    private final Map<String, AtomicStampedReference<List<Long>>> arrivalTimes = new ConcurrentHashMap<String, AtomicStampedReference<List<Long>>>();
    private ScheduledExecutorService scheduler;
    private MonitoringQueuedThreadPool threadPool;
    private HttpClient httpClient;
    private WebSocketClientFactory webSocketClientFactory;

    public static void main(String[] args) throws Exception
    {
        BayeuxLoadClient client = new BayeuxLoadClient();
        client.run();
    }

    public long getResponses()
    {
        return responses.get();
    }

    public long getMessages()
    {
        return messages.get();
    }

    public void run() throws Exception
    {
        System.err.println("detecting timer resolution...");
        SystemTimer systemTimer = SystemTimer.detect();
        System.err.printf("native timer resolution: %d \u00B5s%n", systemTimer.getNativeResolution());
        System.err.printf("emulated timer resolution: %d \u00B5s%n", systemTimer.getEmulatedResolution());
        System.err.println();

        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

        String host = "localhost";
        System.err.printf("server [%s]: ", host);
        String value = console.readLine().trim();
        if (value.length() == 0)
            value = host;
        host = value;

        int port = 8080;
        System.err.printf("port [%d]: ", port);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(port);
        port = Integer.parseInt(value);

        ClientTransportType clientTransportType = ClientTransportType.LONG_POLLING;
        System.err.printf("transports:%n");
        for (ClientTransportType type : ClientTransportType.values())
            System.err.printf("  %d - %s%n", type.ordinal(), type.getName());
        System.err.printf("transport [%d]: ", clientTransportType.ordinal());
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(clientTransportType.ordinal());
        clientTransportType = ClientTransportType.values()[Integer.parseInt(value)];

        boolean ssl = false;
        System.err.printf("use ssl [%b]: ", ssl);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(ssl);
        ssl = Boolean.parseBoolean(value);

        int maxThreads = 256;
        System.err.printf("max threads [%d]: ", maxThreads);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(maxThreads);
        maxThreads = Integer.parseInt(value);

        String contextPath = "/cometd";
        System.err.printf("context [%s]: ", contextPath);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = contextPath;
        String uri = value + "/cometd";
        String url = (ssl ? "https" : "http") + "://" + host + ":" + port + uri;

        String channel = "/chat/demo";
        System.err.printf("channel [%s]: ", channel);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = channel;
        channel = value;

        int rooms = 100;
        System.err.printf("rooms [%d]: ", rooms);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(rooms);
        rooms = Integer.parseInt(value);

        int roomsPerClient = 10;
        System.err.printf("rooms per client [%d]: ", roomsPerClient);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(roomsPerClient);
        roomsPerClient = Integer.parseInt(value);

        boolean recordLatencyDetails = true;
        System.err.printf("record latency details [%b]: ", recordLatencyDetails);
        value = console.readLine().trim();
        if (value.length() == 0)
            value = String.valueOf(recordLatencyDetails);
        recordLatencyDetails = Boolean.parseBoolean(value);

        scheduler = Executors.newScheduledThreadPool(8);

        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        mbContainer.start();
        mbContainer.addBean(this);

        threadPool = new MonitoringQueuedThreadPool(maxThreads);
        threadPool.setDaemon(true);
        threadPool.start();
        mbContainer.addBean(threadPool);

        httpClient = new HttpClient();
        httpClient.setMaxConnectionsPerAddress(50000);
        httpClient.setThreadPool(threadPool);
        httpClient.setIdleTimeout(5000);
//        httpClient.setUseDirectBuffers(false);
        httpClient.start();
        mbContainer.addBean(httpClient);

        webSocketClientFactory = new WebSocketClientFactory(threadPool, new ZeroMaskGen(), 8 * 1024);
        webSocketClientFactory.start();
        mbContainer.addBean(webSocketClientFactory);

        HandshakeListener handshakeListener = new HandshakeListener(channel, rooms, roomsPerClient);
        DisconnectListener disconnectListener = new DisconnectListener();
        LatencyListener latencyListener = new LatencyListener(recordLatencyDetails);

        LoadBayeuxClient statsClient = new LoadBayeuxClient(url, scheduler, newClientTransport(clientTransportType), null);
        statsClient.handshake();

        int clients = 100;
        int batchCount = 1000;
        int batchSize = 10;
        long batchPause = 10000;
        int messageSize = 50;
        boolean randomize = false;

        while (true)
        {
            System.err.println("-----");

            System.err.printf("clients [%d]: ", clients);
            value = console.readLine();
            if (value == null)
                break;
            value = value.trim();
            if (value.length() == 0)
                value = String.valueOf(clients);
            clients = Integer.parseInt(value);

            System.err.println("Waiting for clients to be ready...");

            // Create or remove the necessary bayeux clients
            int currentClients = bayeuxClients.size();
            if (currentClients < clients)
            {
                for (int i = 0; i < clients - currentClients; ++i)
                {
                    LoadBayeuxClient client = new LoadBayeuxClient(url, scheduler, newClientTransport(clientTransportType), latencyListener);
                    client.getChannel(Channel.META_HANDSHAKE).addListener(handshakeListener);
                    client.getChannel(Channel.META_DISCONNECT).addListener(disconnectListener);
                    client.handshake();

                    // Give some time to the server to accept connections and
                    // reply to handshakes, connects and subscribes
                    if (i % 10 == 0)
                        Thread.sleep(50);
                }
            }
            else if (currentClients > clients)
            {
                for (int i = 0; i < currentClients - clients; ++i)
                {
                    LoadBayeuxClient client = bayeuxClients.get(currentClients - i - 1);
                    client.disconnect(1000);
                }
            }

            int maxRetries = 50;
            int retries = maxRetries;
            int lastSize = 0;
            int currentSize = bayeuxClients.size();
            while (currentSize != clients)
            {
                Thread.sleep(250);
                System.err.printf("Waiting for clients %d/%d%n", currentSize, clients);
                if (lastSize == currentSize)
                {
                    --retries;
                    if (retries == 0)
                        break;
                }
                else
                {
                    lastSize = currentSize;
                    retries = maxRetries;
                }
                currentSize = bayeuxClients.size();
            }
            if (currentSize != clients)
            {
                System.err.printf("Clients not ready, only %d/%d%n", currentSize, clients);
                break;
            }
            else
            {
                if (currentSize == 0)
                {
                    System.err.println("All clients disconnected, exiting");
                    break;
                }

                System.err.println("Clients ready");
            }

            reset();

            System.err.printf("batch count [%d]: ", batchCount);
            value = console.readLine().trim();
            if (value.length() == 0)
                value = String.valueOf(batchCount);
            batchCount = Integer.parseInt(value);

            System.err.printf("batch size [%d]: ", batchSize);
            value = console.readLine().trim();
            if (value.length() == 0)
                value = String.valueOf(batchSize);
            batchSize = Integer.parseInt(value);

            System.err.printf("batch pause (\u00B5s) [%d]: ", batchPause);
            value = console.readLine().trim();
            if (value.length() == 0)
                value = String.valueOf(batchPause);
            batchPause = Long.parseLong(value);

            System.err.printf("message size [%d]: ", messageSize);
            value = console.readLine().trim();
            if (value.length() == 0)
                value = String.valueOf(messageSize);
            messageSize = Integer.parseInt(value);
            String chat = "";
            for (int i = 0; i < messageSize; i++)
                chat += "x";

            System.err.printf("randomize sends [%b]: ", randomize);
            value = console.readLine().trim();
            if (value.length() == 0)
                value = String.valueOf(randomize);
            randomize = Boolean.parseBoolean(value);

            // Send a message to the server to signal the start of the test
            statsClient.begin();

            helper.startStatistics();
            System.err.printf("Testing %d clients in %d rooms, %d rooms/client%n", bayeuxClients.size(), rooms, roomsPerClient);
            System.err.printf("Sending %d batches of %dx%d bytes messages every %d \u00B5s%n", batchCount, batchSize, messageSize, batchPause);

            long start = System.nanoTime();
            int clientIndex = -1;
            long expected = 0;
            for (int i = 0; i < batchCount; ++i)
            {
                if (randomize)
                {
                    clientIndex = nextRandom(bayeuxClients.size());
                }
                else
                {
                    ++clientIndex;
                    if (clientIndex >= bayeuxClients.size())
                        clientIndex = 0;
                }
                LoadBayeuxClient client = bayeuxClients.get(clientIndex);

                client.startBatch();
                for (int b = 0; b < batchSize; ++b)
                {
                    int room = -1;
                    AtomicInteger clientsPerRoom = null;
                    while (clientsPerRoom == null || clientsPerRoom.get() == 0)
                    {
                        room = nextRandom(rooms);
                        clientsPerRoom = this.rooms.get(room);
                    }
                    Map<String, Object> message = new HashMap<String, Object>(4);
                    message.put("room", room);
                    message.put("user", clientIndex);
                    message.put("chat", chat);
                    message.put("start", System.nanoTime());
                    ClientSessionChannel clientChannel = client.getChannel(channel + "/" + room);
                    clientChannel.publish(message);
                    clientChannel.release();
                    expected += clientsPerRoom.get();
                }
                client.endBatch();

                if (batchPause > 0)
                    systemTimer.sleep(batchPause);
            }
            long end = System.nanoTime();

            helper.stopStatistics();

            long elapsedNanos = end - start;
            if (elapsedNanos > 0)
            {
                System.err.printf("Outgoing: Elapsed = %d ms | Rate = %d messages/s - %d requests/s - ~%.3f Mib/s%n",
                        TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                        batchCount * batchSize * 1000L * 1000 * 1000 / elapsedNanos,
                        batchCount * 1000L * 1000 * 1000 / elapsedNanos,
                        batchCount * batchSize * messageSize * 8F * 1000 * 1000 * 1000  / elapsedNanos / 1024 / 1024
                        );
            }

            waitForMessages(expected);

            // Send a message to the server to signal the end of the test
            statsClient.end();

            printReport(expected, messageSize);

            reset();
        }

        statsClient.disconnect(1000);

        webSocketClientFactory.stop();

        httpClient.stop();

        threadPool.stop();

        mbContainer.stop();

        scheduler.shutdown();
        scheduler.awaitTermination(1000, TimeUnit.MILLISECONDS);
    }

    private ClientTransport newClientTransport(ClientTransportType clientTransportType)
    {
        switch (clientTransportType)
        {
            case LONG_POLLING:
            {
                Map<String, Object> options = new HashMap<String, Object>();
                options.put(ClientTransport.JSON_CONTEXT, new JacksonJSONContextClient());
                return new LongPollingTransport(options, httpClient);
            }
            case WEBSOCKET:
            {
                Map<String, Object> options = new HashMap<String, Object>();
                options.put(ClientTransport.JSON_CONTEXT, new JacksonJSONContextClient());
                options.put(WebSocketTransport.IDLE_TIMEOUT_OPTION, 35000);
                return new WebSocketTransport(options, webSocketClientFactory, scheduler);
            }
            default:
            {
                throw new IllegalArgumentException();
            }
        }
    }

    private int nextRandom(int limit)
    {
        synchronized (this)
        {
            return random.nextInt(limit);
        }
    }

    private void updateLatencies(long startTime, long sendTime, long arrivalTime, long endTime, boolean recordDetails)
    {
        // Latencies are in nanoseconds, but microsecond accuracy is enough
        long wallLatency = TimeUnit.MICROSECONDS.toNanos(TimeUnit.NANOSECONDS.toMicros(endTime - startTime));
        long latency = TimeUnit.MICROSECONDS.toNanos(TimeUnit.NANOSECONDS.toMicros(arrivalTime - sendTime));

        // Update the latencies using a non-blocking algorithm
        Atomics.updateMin(minWallLatency, wallLatency);
        Atomics.updateMax(maxWallLatency, wallLatency);
        totWallLatency.addAndGet(wallLatency);
        Atomics.updateMin(minLatency, latency);
        Atomics.updateMax(maxLatency, latency);
        totLatency.addAndGet(latency);

        if (recordDetails)
        {
            AtomicLong count = wallLatencies.get(wallLatency);
            if (count == null)
            {
                count = new AtomicLong();
                AtomicLong existing = wallLatencies.putIfAbsent(wallLatency, count);
                if (existing != null)
                    count = existing;
            }
            count.incrementAndGet();
        }
    }

    private boolean waitForMessages(long expected) throws InterruptedException
    {
        long arrived = messages.get();
        long lastArrived = 0;
        int maxRetries = 20;
        int retries = maxRetries;
        while (arrived < expected)
        {
            System.err.printf("Waiting for messages to arrive %d/%d%n", arrived, expected);
            Thread.sleep(500);
            if (lastArrived == arrived)
            {
                --retries;
                if (retries == 0)
                    break;
            }
            else
            {
                lastArrived = arrived;
                retries = maxRetries;
            }
            arrived = messages.get();
        }
        if (arrived < expected)
        {
            System.err.printf("Interrupting wait for messages %d/%d%n", arrived, expected);
            return false;
        }
        else
        {
            System.err.printf("All messages arrived %d/%d%n", arrived, expected);
            return true;
        }
    }

    public void printReport(long expectedCount, int messageSize)
    {
        long messageCount = messages.get();
        System.err.printf("Messages - Success/Expected = %d/%d%n", messageCount, expectedCount);

        long elapsedNanos = end.get() - start.get();
        if (elapsedNanos > 0)
        {
            System.err.printf("Incoming - Elapsed = %d ms | Rate = %d messages/s - %d responses/s(%.2f%%) - ~%.3f Mib/s%n",
                    TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                    messageCount * 1000L * 1000 * 1000 / elapsedNanos,
                    responses.get() * 1000L * 1000 * 1000 / elapsedNanos,
                    100F * responses.get() / messageCount,
                    messageCount * messageSize * 8F * 1000 * 1000 * 1000 / elapsedNanos / 1024 / 1024
                    );
        }

        if (wallLatencies.size() > 1)
        {
            // Needs to be sorted in order to calculate the median (aka latency at 50th percentile)
            Map<Long, AtomicLong> sortedWallLatencies = new TreeMap<Long, AtomicLong>(wallLatencies);
            wallLatencies.clear();

            long messages = 0;
            long maxLatencyBucketFrequency = 0;
            long previousLatency = 0;
            long latencyAt50thPercentile = 0;
            long latencyAt99thPercentile = 0;
            long[] latencyBucketFrequencies = new long[20];
            long minWallLatency = this.minWallLatency.get();
            long latencyRange = maxWallLatency.get() - minWallLatency;
            for (Iterator<Map.Entry<Long, AtomicLong>> entries = sortedWallLatencies.entrySet().iterator(); entries.hasNext();)
            {
                Map.Entry<Long, AtomicLong> entry = entries.next();
                long latency = entry.getKey();
                Long bucketIndex = latencyRange == 0 ? 0 : (latency - minWallLatency) * latencyBucketFrequencies.length / latencyRange;
                int index = bucketIndex.intValue() == latencyBucketFrequencies.length ? latencyBucketFrequencies.length - 1 : bucketIndex.intValue();
                long value = entry.getValue().get();
                messages += value;
                latencyBucketFrequencies[index] += value;
                if (latencyBucketFrequencies[index] > maxLatencyBucketFrequency)
                    maxLatencyBucketFrequency = latencyBucketFrequencies[index];
                if (latencyAt50thPercentile == 0 && messages > messageCount / 2)
                    latencyAt50thPercentile = (previousLatency + latency) / 2;
                if (latencyAt99thPercentile == 0 && messages > messageCount - messageCount / 100)
                    latencyAt99thPercentile = (previousLatency + latency) / 2;
                previousLatency = latency;
                entries.remove();
            }

            if (messages != messageCount)
                System.err.printf("Counted messages (%d) != Latency messages sum (%d)%n", messageCount, messages);

            System.err.println("Messages - Wall Latency Distribution Curve (X axis: Frequency, Y axis: Latency):");
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
                double percentage = 100D * latencyBucketFrequency / messages;
                System.err.print(TimeUnit.NANOSECONDS.toMillis((latencyRange * (i + 1) / latencyBucketFrequencies.length) + minWallLatency));
                System.err.printf(" ms (%d, %.2f%%)", latencyBucketFrequency, percentage);
                double last = percentile;
                percentile += percentage;
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

            System.err.printf("Messages - Wall Latency 50th%%/99th%% = %d/%d ms%n",
                    TimeUnit.NANOSECONDS.toMillis(latencyAt50thPercentile),
                    TimeUnit.NANOSECONDS.toMillis(latencyAt99thPercentile));
        }

        System.err.printf("Messages - Wall Latency Min/Ave/Max = %d/%d/%d ms%n",
                TimeUnit.NANOSECONDS.toMillis(minWallLatency.get()),
                messageCount == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(totWallLatency.get() / messageCount),
                TimeUnit.NANOSECONDS.toMillis(maxWallLatency.get()));

        System.err.printf("Messages - Network Latency Min/Ave/Max = %d/%d/%d ms%n",
                TimeUnit.NANOSECONDS.toMillis(minLatency.get()),
                messageCount == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(totLatency.get() / messageCount),
                TimeUnit.NANOSECONDS.toMillis(maxLatency.get()));

        System.err.printf("Thread Pool - Concurrent Threads max = %d | Queue Size max = %d | Queue Latency avg/max = %d/%d ms%n",
                threadPool.getMaxActiveThreads(),
                threadPool.getMaxQueueSize(),
                TimeUnit.NANOSECONDS.toMillis(threadPool.getAverageQueueLatency()),
                TimeUnit.NANOSECONDS.toMillis(threadPool.getMaxQueueLatency()));
    }

    private void reset()
    {
        threadPool.reset();
        start.set(0L);
        end.set(0L);
        responses.set(0L);
        messages.set(0L);
        minWallLatency.set(Long.MAX_VALUE);
        maxWallLatency.set(0L);
        totWallLatency.set(0L);
        minLatency.set(Long.MAX_VALUE);
        maxLatency.set(0L);
        totLatency.set(0L);
        wallLatencies.clear();
        sendTimes.clear();
        arrivalTimes.clear();
    }

    private class HandshakeListener implements ClientSessionChannel.MessageListener
    {
        private static final String SESSION_ID_ATTRIBUTE = "handshook";
        private final String channel;
        private final int rooms;
        private final int roomsPerClient;

        private HandshakeListener(String channel, int rooms, int roomsPerClient)
        {
            this.channel = channel;
            this.rooms = rooms;
            this.roomsPerClient = roomsPerClient;
        }

        public void onMessage(ClientSessionChannel channel, Message message)
        {
            if (message.isSuccessful())
            {
                final LoadBayeuxClient client = (LoadBayeuxClient)channel.getSession();

                String sessionId = (String)client.getAttribute(SESSION_ID_ATTRIBUTE);
                if (sessionId == null)
                {
                    client.setAttribute(SESSION_ID_ATTRIBUTE, client.getId());
                    bayeuxClients.add(client);

                    client.batch(new Runnable()
                    {
                        public void run()
                        {
                            List<Integer> roomsSubscribedTo = new ArrayList<Integer>();
                            for (int j = 0; j < roomsPerClient; ++j)
                            {
                                // Avoid to subscribe the same client twice to the same room
                                int room = nextRandom(rooms);
                                while (roomsSubscribedTo.contains(room))
                                    room = nextRandom(rooms);
                                roomsSubscribedTo.add(room);
                                client.init(HandshakeListener.this.channel, room);
                            }
                        }
                    });
                }
                else
                {
                    System.err.printf("Second handshake for client %s: old session %s, new session %s%n", this, sessionId, client.getId());
                }
            }
        }
    }

    private class DisconnectListener implements ClientSessionChannel.MessageListener
    {
        public void onMessage(ClientSessionChannel channel, Message message)
        {
            if (message.isSuccessful())
            {
                LoadBayeuxClient client = (LoadBayeuxClient)channel.getSession();
                bayeuxClients.remove(client);
                client.destroy();
            }
        }
    }

    private class LatencyListener implements ClientSessionChannel.MessageListener
    {
        private final boolean recordDetails;

        public LatencyListener(boolean recordDetails)
        {
            this.recordDetails = recordDetails;
        }

        public void onMessage(ClientSessionChannel channel, Message message)
        {
            Map<String, Object> data = message.getDataAsMap();
            if (data != null)
            {
                Long startTime = ((Number)data.get("start")).longValue();
                if (startTime != null)
                {
                    long endTime = System.nanoTime();
                    if (start.get() == 0L)
                        start.set(endTime);
                    end.set(endTime);
                    messages.incrementAndGet();

                    String messageId = message.getId();

                    AtomicStampedReference<Long> sendTimeRef = sendTimes.get(messageId);
                    long sendTime = sendTimeRef.getReference();
                    // Update count atomically
                    if (Atomics.decrement(sendTimeRef) == 0)
                        sendTimes.remove(messageId);

                    AtomicStampedReference<List<Long>> arrivalTimeRef = arrivalTimes.get(messageId);
                    long arrivalTime = arrivalTimeRef.getReference().remove(0);
                    // Update count atomically
                    if (Atomics.decrement(arrivalTimeRef) == 0)
                        arrivalTimes.remove(messageId);

                    updateLatencies(startTime, sendTime, arrivalTime, endTime, recordDetails);
                }
                else
                {
                    throw new IllegalStateException("No 'start' field in message " + message);
                }
            }
            else
            {
                throw new IllegalStateException("No 'data' field in message " + message);
            }
        }
    }

    private class LoadBayeuxClient extends BayeuxClient
    {
        private final List<Integer> subscriptions = new ArrayList<Integer>();
        private final ClientSessionChannel.MessageListener latencyListener;

        private LoadBayeuxClient(String url, ScheduledExecutorService scheduler, ClientTransport transport, ClientSessionChannel.MessageListener listener)
        {
            super(url, scheduler, transport);
            this.latencyListener = listener;
        }

        public void init(String channel, int room)
        {
            if (latencyListener != null)
                getChannel(channel + "/" + room).subscribe(latencyListener);

            AtomicInteger clientsPerRoom = rooms.get(room);
            if (clientsPerRoom == null)
            {
                clientsPerRoom = new AtomicInteger();
                AtomicInteger existing = rooms.putIfAbsent(room, clientsPerRoom);
                if (existing != null)
                    clientsPerRoom = existing;
            }
            clientsPerRoom.incrementAndGet();

            subscriptions.add(room);
        }

        public void destroy()
        {
            for (Integer room : subscriptions)
            {
                AtomicInteger clientsPerRoom = rooms.get(room);
                clientsPerRoom.decrementAndGet();
            }
            subscriptions.clear();
        }

        public void begin() throws InterruptedException
        {
            notifyServer("/service/statistics/start");
        }

        public void end() throws InterruptedException
        {
            notifyServer("/service/statistics/stop");
        }

        private void notifyServer(String channelName) throws InterruptedException
        {
            final CountDownLatch latch = new CountDownLatch(1);
            ClientSessionChannel channel = getChannel(channelName);
            channel.addListener(new ClientSessionChannel.MessageListener()
            {
                public void onMessage(ClientSessionChannel channel, Message message)
                {
                    channel.removeListener(this);
                    latch.countDown();
                }
            });
            channel.publish(new HashMap<String, Object>(1));
            latch.await();
        }

        @Override
        public void onSending(Message[] messages)
        {
            long now = System.nanoTime();
            for (Message message : messages)
            {
                Map<String, Object> data = message.getDataAsMap();
                if (data != null && message.getChannelId().isBroadcast())
                {
                    int room = (Integer)data.get("room");
                    int clientsInRoom = rooms.get(room).get();
                    sendTimes.put(message.getId(), new AtomicStampedReference<Long>(now, clientsInRoom));
                    // There is no write-cheap concurrent list in JDK, so let's use a synchronized wrapper
                    arrivalTimes.put(message.getId(), new AtomicStampedReference<List<Long>>(Collections.synchronizedList(new LinkedList<Long>()), clientsInRoom));
                }
            }
        }

        @Override
        public void onMessages(List<Message.Mutable> messages)
        {
            long now = System.nanoTime();
            boolean response = false;
            for (Message message : messages)
            {
                if (message.getData() != null)
                {
                    response = true;
                    arrivalTimes.get(message.getId()).getReference().add(now);
                }
            }
            if (response)
                responses.incrementAndGet();
        }
    }

    private enum ClientTransportType
    {
        LONG_POLLING("long-polling"), WEBSOCKET("websocket");

        private final String name;

        private ClientTransportType(String name)
        {
            this.name = name;
        }

        public String getName()
        {
            return name;
        }
    }
}
