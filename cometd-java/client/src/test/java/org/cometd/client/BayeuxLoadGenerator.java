package org.cometd.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ajax.JSON;
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
    private final HttpClient httpClient;

    public static void main(String[] args) throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setMaxConnectionsPerAddress(40000);
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(500);
        threadPool.setDaemon(true);
        httpClient.setThreadPool(threadPool);
        httpClient.setIdleTimeout(5000);
        httpClient.start();

        BayeuxLoadGenerator generator = new BayeuxLoadGenerator(httpClient);
        generator.generateLoad();
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

        DisconnectListener disconnectListener = new DisconnectListener();
        LatencyListener latencyListener = new LatencyListener();

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
                    LoadBayeuxClient client = new LoadBayeuxClient(url, httpClient, channel, rooms, roomsPerClient, latencyListener);
                    client.addListener(disconnectListener);
                    client.start();

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
            LoadBayeuxClient statsClient = bayeuxClients.get(0);
            statsClient.begin();

            helper.startStatistics();

            long start = System.nanoTime();
            int clientIndex = -1;
            long expected = 0;
            StringBuilder message = new StringBuilder();
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
                    message.setLength(0);
                    message.append("{").append(partialMessage).append(System.nanoTime()).append("}");
                    client.publish(channel + "/" + room, new JSON.Literal(message.toString()), String.valueOf(messageIds.incrementAndGet()));
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
                System.err.print("Outgoing: Elapsed | Rate = ");
                System.err.print(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
                System.err.print(" ms | ");
                System.err.print(batchCount * batchSize * 1000L * 1000L * 1000L / elapsedNanos);
                System.err.print(" messages/s - ");
                System.err.print(batchCount * 1000L * 1000L * 1000L / elapsedNanos);
                System.err.println(" requests/s");
            }

            waitForMessages(expected);

            // Send a message to the server to signal the end of the test
            statsClient.end();

            printReport(expected);
        }

        httpClient.stop();
    }

    private void updateLatencies(long startTime, long endTime)
    {
        long wallLatency = endTime - startTime;

        // Update the latencies using a non-blocking algorithm
        updateMin(minWallLatency, wallLatency);
        updateMax(maxWallLatency, wallLatency);
        totWallLatency.addAndGet(wallLatency);

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
            System.err.print("Incoming - Elapsed | Rate = ");
            System.err.print(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
            System.err.print(" ms | ");
            System.err.print(messageCount * 1000L * 1000L * 1000L / elapsedNanos);
            System.err.print(" messages/s - ");
            System.err.print(responses.get() * 1000L * 1000L * 1000L / elapsedNanos);
            System.err.println(" responses/s");
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
                System.err.println(" ms (" + latencyBucketFrequency + ")");
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

    private class DisconnectListener implements MessageListener
    {
        public void deliver(Client fromClient, Client toClient, Message message)
        {
            if (Bayeux.META_DISCONNECT.equals(message.get(Bayeux.CHANNEL_FIELD)) &&
                (Boolean)message.get(Bayeux.SUCCESSFUL_FIELD))
            {
                toClient.removeListener(this);
                bayeuxClients.remove(toClient);
            }
        }
    }

    private class LatencyListener implements MessageListener
    {
        public void deliver(Client fromClient, Client toClient, Message message)
        {
            Object data = message.get(Bayeux.DATA_FIELD);
            if (data != null)
            {
                String msgId = (String)message.get(Bayeux.ID_FIELD);
                if (msgId != null)
                {
                    long arrivalTime = System.nanoTime();
                    if (start.get() == 0L)
                        start.set(arrivalTime);
                    end.set(arrivalTime);
                    messages.incrementAndGet();
                    updateLatencies(Long.parseLong(msgId), arrivalTime);
                }
            }
        }
    }

    private class LoadBayeuxClient extends BayeuxClient
    {
        private final List<Integer> subscriptions = new ArrayList<Integer>();
        private final String channel;
        private final int rooms;
        private final int roomsPerClient;
        private final MessageListener listener;

        private LoadBayeuxClient(String url, HttpClient client, String channel, int rooms, int roomsPerClient, MessageListener listener)
        {
            super(client, url);
            this.channel = channel;
            this.rooms = rooms;
            this.roomsPerClient = roomsPerClient;
            this.listener = listener;
        }

        @Override
        protected void metaHandshake(boolean success, boolean reestablish, Message message)
        {
            if (success)
            {
                bayeuxClients.add(this);
                startBatch();
                try
                {
                    for (int j = 0; j < roomsPerClient; ++j)
                    {
                        int room = nextRandom(rooms);
                        init(channel, room);
                    }
                }
                finally
                {
                    endBatch();
                }
            }
        }

        public void init(String channel, int room)
        {
            addListener(listener);
            subscribe(channel + "/" + room);

            Integer clientsPerRoom = BayeuxLoadGenerator.this.rooms.get(room);
            if (clientsPerRoom == null) clientsPerRoom = 0;
            BayeuxLoadGenerator.this.rooms.put(room, ++clientsPerRoom);

            subscriptions.add(room);
        }

        public void destroy()
        {
            disconnect();

            for (Integer room : subscriptions)
            {
                Integer clientsPerRoom = BayeuxLoadGenerator.this.rooms.get(room);
                BayeuxLoadGenerator.this.rooms.put(room, --clientsPerRoom);
            }

            subscriptions.clear();
        }

        public void begin()
        {
            publish("/service/statistics/start", new HashMap<String, Object>(), null);
        }

        public void end()
        {
            publish("/service/statistics/stop", new HashMap<String, Object>(), null);
        }
    }
}
