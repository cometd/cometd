package org.cometd.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.transport.LongPollingTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class BayeuxLoadGenerator
{
    private final Random random = new Random();
    private final StatisticsHelper helper = new StatisticsHelper();
    private final List<LoadBayeuxClient> bayeuxClients = Collections.synchronizedList(new ArrayList<LoadBayeuxClient>());
    private final Map<Integer, Integer> rooms = new HashMap<Integer, Integer>();
    private final AtomicLong messageIds = new AtomicLong();
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
    private final Map<String, Long> sendTimes = new ConcurrentHashMap<String, Long>();
    private final Map<String, Long> arrivalTimes = new ConcurrentHashMap<String, Long>();
    private final HttpClient httpClient;
    private volatile boolean running;

    public long getMessages()
    {
        return messages.get();
    }

    public long getResponses()
    {
        return responses.get();
    }

    public boolean isRunning()
    {
        return running;
    }

    public void setRunning(boolean running)
    {
        this.running = running;
    }

    public static void main(String[] args) throws Exception
    {
        try
        {
            HttpClient httpClient = new HttpClient();
            httpClient.setMaxConnectionsPerAddress(40000);
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setMaxThreads(256);
            threadPool.setDaemon(true);
            httpClient.setThreadPool(threadPool);
            httpClient.setIdleTimeout(5000);
//            httpClient.setUseDirectBuffers(false);
            httpClient.start();

            BayeuxLoadGenerator generator = new BayeuxLoadGenerator(httpClient);


            MBeanContainer mbContainer=new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
            mbContainer.addBean(httpClient);
            mbContainer.addBean(threadPool);
            mbContainer.addBean(generator);
            mbContainer.addBean(Log.getLog());

            generator.generateLoad();
        }
        catch (Exception x)
        {
            x.printStackTrace();
            throw x;
        }
    }

    public BayeuxLoadGenerator(HttpClient httpClient)
    {
        this.httpClient = httpClient;
    }

    private int nextRandom(int limit)
    {
        synchronized (this)
        {
            return random.nextInt(limit);
        }
    }

    public void generateLoad() throws Exception
    {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

        System.err.print("protocol [http]: ");
        String value = console.readLine().trim();
        if (value.length() == 0)
            value = "http";
        String protocol = value;

        System.err.print("server [localhost]: ");
        value = console.readLine().trim();
        if (value.length() == 0)
            value = "localhost";
        String host = value;

        System.err.print("port [8080]: ");
        value = console.readLine().trim();
        if (value.length() == 0)
            value = "8080";
        int port = Integer.parseInt(value);

        System.err.print("context [/cometd]: ");
        value = console.readLine().trim();
        if (value.length() == 0)
            value = "/cometd";
        String uri = value + "/cometd";

        String url = protocol + "://" + host + ":" + port + uri;

        int rooms = 100;
        int roomsPerClient = 1;
        int clients = 100;
        int batchCount = 1000;
        int batchSize = 10;
        long batchPause = 10000;
        int messageSize = 50;
        boolean randomize = true;

        System.err.print("channel [/chat/demo]: ");
        value = console.readLine().trim();
        if (value.length() == 0)
            value = "/chat/demo";
        String channel = value;

        System.err.print("rooms [" + rooms + "]: ");
        value = console.readLine().trim();
        if (value.length() == 0)
            value = "" + rooms;
        rooms = Integer.parseInt(value);

        System.err.print("rooms per client [" + roomsPerClient + "]: ");
        value = console.readLine().trim();
        if (value.length() == 0)
            value = "" + roomsPerClient;
        roomsPerClient = Integer.parseInt(value);

        HandshakeListener handshakeListener = new HandshakeListener(channel, rooms, roomsPerClient);
        DisconnectListener disconnectListener = new DisconnectListener();
        LatencyListener latencyListener = new LatencyListener();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);

        LoadBayeuxClient statsClient = new LoadBayeuxClient(url, scheduler, httpClient, null);
        statsClient.handshake();

        while (true)
        {
            System.err.println("-----");

            System.err.print("clients [" + clients + "]: ");
            value = console.readLine();
            if (value == null)
                break;
            value = value.trim();
            if (value.length() == 0)
                value = "" + clients;
            clients = Integer.parseInt(value);

            System.err.println("Waiting for clients to be ready...");

            // Create or remove the necessary bayeux clients
            int currentClients = bayeuxClients.size();
            if (currentClients < clients)
            {
                for (int i = 0; i < clients - currentClients; ++i)
                {
                    LoadBayeuxClient client = new LoadBayeuxClient(url, scheduler, httpClient, latencyListener);
                    client.getChannel(Channel.META_HANDSHAKE).addListener(handshakeListener);
                    client.getChannel(Channel.META_DISCONNECT).addListener(disconnectListener);
                    client.handshake();

                    // Give some time to the server to accept connections and
                    // reply to handshakes, connects and subscribes
                    if (i % 10 == 0)
                    {
                        sleep(100000);
                    }
                }
            }
            else if (currentClients > clients)
            {
                for (int i = 0; i < currentClients - clients; ++i)
                {
                    LoadBayeuxClient client = bayeuxClients.get(currentClients - i - 1);
                    client.destroy();
                }
            }

            int maxRetries = 50;
            int retries = maxRetries;
            int lastSize = 0;
            int currentSize = bayeuxClients.size();
            while (currentSize != clients)
            {
                sleep(250000);
                System.err.println("Waiting for clients " + currentSize + "/" + clients);
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
                System.err.println("Clients not ready, only " + currentSize + "/" + clients);
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

            System.err.print("batch count [" + batchCount + "]: ");
            value = console.readLine().trim();
            if (value.length() == 0)
                value = "" + batchCount;
            batchCount = Integer.parseInt(value);

            System.err.print("batch size [" + batchSize + "]: ");
            value = console.readLine().trim();
            if (value.length() == 0)
                value = "" + batchSize;
            batchSize = Integer.parseInt(value);

            System.err.print("batch pause (\u00B5s) [" + batchPause + "]: ");
            value = console.readLine().trim();
            if (value.length() == 0)
                value = "" + batchPause;
            batchPause = Long.parseLong(value);

            System.err.print("message size [" + messageSize + "]: ");
            value = console.readLine().trim();
            if (value.length() == 0)
                value = "" + messageSize;
            messageSize = Integer.parseInt(value);
            String chat = "";
            for (int i = 0; i < messageSize; i++)
                chat += "x";

            System.err.print("randomize sends [" + randomize + "]: ");
            value = console.readLine().trim();
            if (value.length() == 0)
                value = "" + randomize;
            randomize = Boolean.parseBoolean(value);

            // Send a message to the server to signal the start of the test
            statsClient.begin();

            helper.startStatistics();
            System.err.println("Testing "+bayeuxClients.size()+" clients in "+rooms+" rooms\nSending "+batchCount+" batches of "+batchSize+"x"+messageSize+"B messages every "+batchPause+"\u00B5s");

            long start = System.nanoTime();
            int clientIndex = -1;
            long expected = 0;
            StringBuilder message = new StringBuilder();
            running=true;
            for (int i = 0; i < batchCount; ++i)
            {
                if (!running)
                    break;

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
                String partialMessage = new StringBuilder()
                        .append("\"user\":").append(clientIndex).append(",")
                        .append("\"chat\":\"").append(chat).append("\",")
                        .append("\"start\":").toString();
                client.startBatch();
                for (int b = 0; b < batchSize; ++b)
                {
                    int room = -1;
                    Integer clientsPerRoom = null;
                    while (clientsPerRoom == null || clientsPerRoom == 0)
                    {
                        room = nextRandom(rooms);
                        clientsPerRoom = this.rooms.get(room);
                    }
                    ClientSessionChannel clientChannel = client.getChannel(channel + "/" + room);
                    message.setLength(0);
                    message.append("{").append(partialMessage).append(System.nanoTime()).append("}");
                    clientChannel.publish(new JSON.Literal(message.toString()), String.valueOf(messageIds.incrementAndGet()));
                    expected += clientsPerRoom;
                }
                client.endBatch();

                if (batchPause > 0)
                    sleep(batchPause);
            }
            long end = System.nanoTime();

            helper.stopStatistics();

            long elapsedNanos = end - start;
            if (elapsedNanos > 0)
            {
                System.err.print("Outgoing: Elapsed = ");
                System.err.print(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
                System.err.print(" ms | Rate = ");
                System.err.print(batchCount * batchSize * 1000L * 1000L * 1000L / elapsedNanos);
                System.err.print(" messages/s - ");
                System.err.print(batchCount * 1000L * 1000L * 1000L / elapsedNanos);
                System.err.println(" requests/s");
            }

            waitForMessages(expected);

            // Send a message to the server to signal the end of the test
            statsClient.end();

            printReport(expected);

            reset();
        }

        statsClient.disconnect();
        statsClient.waitFor(1000, BayeuxClient.State.DISCONNECTED);

        scheduler.shutdown();
        scheduler.awaitTermination(1000, TimeUnit.MILLISECONDS);

        httpClient.stop();
    }

    private void updateLatencies(long startTime, long sendTime, long arrivalTime, long endTime)
    {
        long wallLatency = endTime - startTime;
        long latency = arrivalTime - sendTime;

        // Update the latencies using a non-blocking algorithm
        updateMin(minWallLatency, wallLatency);
        updateMax(maxWallLatency, wallLatency);
        totWallLatency.addAndGet(wallLatency);
        updateMin(minLatency, latency);
        updateMax(maxLatency, latency);
        totLatency.addAndGet(latency);

        wallLatencies.putIfAbsent(wallLatency, new AtomicLong(0L));
        wallLatencies.get(wallLatency).incrementAndGet();
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

    private boolean waitForMessages(long expected) throws InterruptedException
    {
        long arrived = messages.get();
        long lastArrived = 0;
        int maxRetries = 20;
        int retries = maxRetries;
        while (arrived < expected)
        {
            System.err.println("Waiting for messages to arrive " + arrived + "/" + expected);
            sleep(500000);
            if (lastArrived == arrived)
            {
                --retries;
                if (retries == 0) break;
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
            System.err.println("Interrupting wait for messages " + arrived + "/" + expected);
            return false;
        }
        else
        {
            System.err.println("All messages arrived " + arrived + "/" + expected);
            return true;
        }
    }

    public void printReport(long expectedCount)
    {
        long messageCount = messages.get();
        System.err.print("Messages - Success/Expected = ");
        System.err.print(messageCount);
        System.err.print("/");
        System.err.println(expectedCount);

        long elapsedNanos = end.get() - start.get();
        if (elapsedNanos > 0)
        {
            System.err.print("Incoming - Elapsed = ");
            System.err.print(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
            System.err.print(" ms | Rate = ");
            System.err.print(messageCount * 1000L * 1000L * 1000L / elapsedNanos);
            System.err.print(" messages/s - ");
            System.err.print(responses.get() * 1000L * 1000L * 1000L / elapsedNanos);
            System.err.printf(" responses/s (%.2f%%)\n", 100.0 * responses.get() / messageCount);
        }

        if (wallLatencies.size() > 1)
        {
            long maxLatencyBucketFrequency = 0L;
            long[] latencyBucketFrequencies = new long[20];
            long latencyRange = maxWallLatency.get() - minWallLatency.get();
            for (Iterator<Map.Entry<Long, AtomicLong>> entries = wallLatencies.entrySet().iterator(); entries.hasNext();)
            {
                Map.Entry<Long, AtomicLong> entry = entries.next();
                long latency = entry.getKey();
                Long bucketIndex = latencyRange == 0 ? 0 : (latency - minWallLatency.get()) * latencyBucketFrequencies.length / latencyRange;
                int index = bucketIndex.intValue() == latencyBucketFrequencies.length ? latencyBucketFrequencies.length - 1 : bucketIndex.intValue();
                long value = entry.getValue().get();
                latencyBucketFrequencies[index] += value;
                if (latencyBucketFrequencies[index] > maxLatencyBucketFrequency) maxLatencyBucketFrequency = latencyBucketFrequencies[index];
                entries.remove();
            }

            System.err.println("Messages - Wall Latency Distribution Curve (X axis: Frequency, Y axis: Latency):");
            double percentile=0.0;

            for (int i = 0; i < latencyBucketFrequencies.length; ++i)
            {
                long latencyBucketFrequency = latencyBucketFrequencies[i];
                int value = maxLatencyBucketFrequency == 0 ? 0 : Math.round(latencyBucketFrequency * (float) latencyBucketFrequencies.length / maxLatencyBucketFrequency);
                if (value == latencyBucketFrequencies.length) value = value - 1;
                for (int j = 0; j < value; ++j) System.err.print(" ");
                System.err.print("@");
                for (int j = value + 1; j < latencyBucketFrequencies.length; ++j) System.err.print(" ");
                System.err.print("  _  ");
                System.err.print(TimeUnit.NANOSECONDS.toMillis((latencyRange * (i + 1) / latencyBucketFrequencies.length) + minWallLatency.get()));
                System.err.printf(" ms (%d, %.2f%%)",latencyBucketFrequency,(100.0*latencyBucketFrequency/messageCount));
                double last=percentile;
                percentile+=(100.0*latencyBucketFrequency/messageCount);
                if (last<50.0 && percentile>=50.0)
                    System.err.print(" ^50%");
                if (last<85.0 && percentile>=85.0)
                    System.err.print(" ^85%");
                if (last<95.0 && percentile>=95.0)
                    System.err.print(" ^95%");
                if (last<99.0 && percentile>=99.0)
                    System.err.print(" ^99%");
                if (last<99.9 && percentile>=99.9)
                    System.err.print(" ^99.9%");
                System.err.println();
            }
        }

        System.err.print("Messages - Wall Latency Min/Ave/Max = ");
        System.err.print(TimeUnit.NANOSECONDS.toMillis(minWallLatency.get()) + "/");
        System.err.print(messageCount == 0 ? "-/" : TimeUnit.NANOSECONDS.toMillis(totWallLatency.get() / messageCount) + "/");
        System.err.println(TimeUnit.NANOSECONDS.toMillis(maxWallLatency.get()) + " ms");

        System.err.print("Messages - Network Latency Min/Ave/Max = ");
        System.err.print(TimeUnit.NANOSECONDS.toMillis(minLatency.get()) + "/");
        System.err.print(messageCount == 0 ? "-/" : TimeUnit.NANOSECONDS.toMillis(totLatency.get() / messageCount) + "/");
        System.err.println(TimeUnit.NANOSECONDS.toMillis(maxLatency.get()) + " ms");
    }

    private void reset()
    {
        messageIds.set(0L);
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

    /**
     * <p>Unfortunately, {@link Thread#sleep(long)} on many platforms has a resolution of 1 ms
     * or even of 10 ms, so calling <code>Thread.sleep(2)</code> often results in a 10 ms sleep.<br/>
     * The same applies for {@link Thread#sleep(long, int)} and {@link Object#wait(long, int)}:
     * they are not accurate.</p>
     * <p>This is not good since we need to be able to control more accurately the request rate.</p>
     * <p>{@link System#nanoTime()} is precise enough, but we would need to loop continuously
     * checking the nano time until the sleep period is elapsed; to avoid busy looping, this
     * method calls {@link Thread#yield()}.</p>
     *
     * @param micros the microseconds to sleep
     * @throws InterruptedException if interrupted while sleeping
     */
    private void sleep(long micros) throws InterruptedException
    {
        if (micros >= 10000)
        {
            TimeUnit.MICROSECONDS.sleep(micros);
        }
        else
        {
            long end = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(micros);
            while (System.nanoTime() < end)
                Thread.yield();
        }
    }

    private class HandshakeListener implements ClientSessionChannel.MessageListener
    {
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
                bayeuxClients.add(client);
                client.batch(new Runnable()
                {
                    public void run()
                    {
                        for (int j = 0; j < roomsPerClient; ++j)
                        {
                            int room = nextRandom(rooms);
                            client.init(HandshakeListener.this.channel, room);
                        }
                    }
                });
            }
        }
    }

    private class DisconnectListener implements ClientSessionChannel.MessageListener
    {
        public void onMessage(ClientSessionChannel channel, Message message)
        {
            if (message.isSuccessful())
                bayeuxClients.remove((LoadBayeuxClient)channel.getSession());
        }
    }

    private class LatencyListener implements ClientSessionChannel.MessageListener
    {
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
                    Long sendTime = sendTimes.get(messageId);
                    Long arrivalTime = arrivalTimes.get(messageId);
                    if (sendTime != null && arrivalTime != null)
                        updateLatencies(startTime, sendTime, arrivalTime, endTime);
                }
            }
        }
    }

    private class LoadBayeuxClient extends BayeuxClient
    {
        private final List<Integer> subscriptions = new ArrayList<Integer>();
        private final ClientSessionChannel.MessageListener latencyListener;

        private LoadBayeuxClient(String url, ScheduledExecutorService scheduler, HttpClient httpClient, ClientSessionChannel.MessageListener listener)
        {
            super(url, scheduler, LongPollingTransport.create(null, httpClient));
            this.latencyListener = listener;
        }

        public void init(String channel, int room)
        {
            getChannel(channel + "/" + room).subscribe(latencyListener);

            Integer clientsPerRoom = rooms.get(room);
            if (clientsPerRoom == null) clientsPerRoom = 0;
            rooms.put(room, ++clientsPerRoom);

            subscriptions.add(room);
        }

        public void destroy()
        {
            disconnect();

            for (Integer room : subscriptions)
            {
                Integer clientsPerRoom = rooms.get(room);
                rooms.put(room, --clientsPerRoom);
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
            channel.publish(new HashMap<String, Object>());
            latch.await();
        }

        @Override
        public void onSending(Message[] messages)
        {
            long now = System.nanoTime();
            for (Message message : messages)
            {
                if (message.getData() != null)
                {
                    sendTimes.put(message.getId(), now);
                }
            }
        }

        @Override
        public void onMessages(List<Message.Mutable> messages)
        {
            boolean response = false;
            long now = System.nanoTime();
            for (Message message : messages)
            {
                if (message.getData() != null)
                {
                    response = true;
                    arrivalTimes.put(message.getId(), now);
                }
            }
            if (response)
                responses.incrementAndGet();
        }
    }



}
