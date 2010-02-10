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
import org.cometd.ClientListener;
import org.cometd.Message;
import org.cometd.MessageListener;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class BayeuxLoadGenerator
{
    private final List<LoadBayeuxClient> bayeuxClients = Collections.synchronizedList(new ArrayList<LoadBayeuxClient>());
    private final Map<Integer, Integer> rooms = new HashMap<Integer, Integer>();
    private final AtomicLong start = new AtomicLong();
    private final AtomicLong end = new AtomicLong();
    private final AtomicLong messages = new AtomicLong();
    private final AtomicLong minLatency = new AtomicLong();
    private final AtomicLong maxLatency = new AtomicLong();
    private final AtomicLong totLatency = new AtomicLong();
    private final ConcurrentMap<Long, AtomicLong> latencies = new ConcurrentHashMap<Long, AtomicLong>();
    private final ClientListener subscribeListener = new SubscribeListener();
    private final ClientListener latencyListener = new LatencyListener();
    private final ClientListener disconnectListener = new DisconnectListener();
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

    public void generateLoad() throws Exception
    {
        Random random = new Random();

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
        long batchPause = 10;
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
                    LoadBayeuxClient client = new LoadBayeuxClient(httpClient, url);
                    client.addListener(subscribeListener);
                    client.addListener(latencyListener);
                    client.start();

                    client.startBatch();
                    for (int j = 0; j < roomsPerClient; ++j)
                    {
                        int room = random.nextInt(rooms);
                        client.init(channel, room);
                    }
                    client.endBatch();

                    // Give some time to the server to accept connections and
                    // reply to handshakes, connects and subscribes
                    if (i % 10 == 0)
                    {
                        Thread.sleep(100);
                    }
                }
            }
            else if (currentClients > clients)
            {
                for (int i = 0; i < currentClients - clients; ++i)
                {
                    LoadBayeuxClient client = bayeuxClients.get(i);
                    client.addListener(disconnectListener);
                    client.destroy();
                }
            }

            int maxRetries = 60;
            int retries = maxRetries;
            int lastSize = 0;
            int currentSize = bayeuxClients.size();
            while (currentSize != clients)
            {
                Thread.sleep(250);
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
                    break;

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

            System.err.print("batch pause [" + batchPause + "]: ");
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

            long start = System.nanoTime();
            int clientIndex = -1;
            long expected = 0;
            for (int i = 0; i < batchCount; ++i)
            {
                if (randomize)
                {
                    clientIndex = random.nextInt(bayeuxClients.size());
                }
                else
                {
                    ++clientIndex;
                    if (clientIndex >= bayeuxClients.size())
                        clientIndex = 0;
                }
                BayeuxClient client = bayeuxClients.get(clientIndex);
                Object message = new JSON.Literal("{\"user\":\"User-" + clientIndex + "\",\"chat\":\"" + chat + "\"}");
                client.startBatch();
                for (int b = 0; b < batchSize; ++b)
                {
                    int room = -1;
                    Integer clientsPerRoom = null;
                    while (clientsPerRoom == null || clientsPerRoom == 0)
                    {
                        room = random.nextInt(rooms);
                        clientsPerRoom = this.rooms.get(room);
                    }
                    client.publish(channel + "/" + room, message, "" + System.nanoTime());
                    expected += clientsPerRoom;
                }
                client.endBatch();

                if (batchPause > 0)
                    Thread.sleep(batchPause);
            }
            long end = System.nanoTime();
            long elapsedNanos = end - start;
            if (elapsedNanos > 0)
            {
                System.err.print("Messages - Elapsed | Rate = ");
                System.err.print(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
                System.err.print(" ms | ");
                System.err.print(batchCount * batchSize * 1000 * 1000 * 1000 / elapsedNanos);
                System.err.println(" sends/s ");
            }

            waitForMessages(expected);
            printReport(expected);
        }
    }

    private void updateLatencies(long start, long end)
    {
        long latency = end - start;

        // Update the latencies using a non-blocking algorithm
        long oldMinLatency = minLatency.get();
        while (latency < oldMinLatency)
        {
            if (minLatency.compareAndSet(oldMinLatency, latency)) break;
            oldMinLatency = minLatency.get();
        }
        long oldMaxLatency = maxLatency.get();
        while (latency > oldMaxLatency)
        {
            if (maxLatency.compareAndSet(oldMaxLatency, latency)) break;
            oldMaxLatency = maxLatency.get();
        }
        totLatency.addAndGet(latency);

        latencies.putIfAbsent(latency, new AtomicLong(0L));
        latencies.get(latency).incrementAndGet();
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
            Thread.sleep(500);
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
        long responseCount = messages.get();
        System.err.print("Messages - Success/Expected = ");
        System.err.print(responseCount);
        System.err.print("/");
        System.err.println(expectedCount);

        long elapsedNanos = end.get() - start.get();
        if (elapsedNanos > 0)
        {
            System.err.print("Messages - Elapsed/Rate = ");
            System.err.print(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
            System.err.print(" ms / ");
            System.err.print(responseCount * 1000 * 1000 * 1000 / elapsedNanos);
            System.err.println(" messages/s ");
        }

        if (latencies.size() > 1)
        {
            long maxLatencyBucketFrequency = 0L;
            long[] latencyBucketFrequencies = new long[20];
            long latencyRange = maxLatency.get() - minLatency.get();
            for (Iterator<Map.Entry<Long, AtomicLong>> entries = latencies.entrySet().iterator(); entries.hasNext();)
            {
                Map.Entry<Long, AtomicLong> entry = entries.next();
                long latency = entry.getKey();
                Long bucketIndex = latencyRange == 0 ? 0 : (latency - minLatency.get()) * latencyBucketFrequencies.length / latencyRange;
                int index = bucketIndex.intValue() == latencyBucketFrequencies.length ? latencyBucketFrequencies.length - 1 : bucketIndex.intValue();
                long value = entry.getValue().get();
                latencyBucketFrequencies[index] += value;
                if (latencyBucketFrequencies[index] > maxLatencyBucketFrequency) maxLatencyBucketFrequency = latencyBucketFrequencies[index];
                entries.remove();
            }

            System.err.println("Messages - Latency Distribution Curve (X axis: Frequency, Y axis: Latency):");
            for (int i = 0; i < latencyBucketFrequencies.length; ++i)
            {
                long latencyBucketFrequency = latencyBucketFrequencies[i];
                int value = maxLatencyBucketFrequency == 0 ? 0 : Math.round(latencyBucketFrequency * (float) latencyBucketFrequencies.length / maxLatencyBucketFrequency);
                if (value == latencyBucketFrequencies.length) value = value - 1;
                for (int j = 0; j < value; ++j) System.err.print(" ");
                System.err.print("@");
                for (int j = value + 1; j < latencyBucketFrequencies.length; ++j) System.err.print(" ");
                System.err.print("  _  ");
                System.err.print(TimeUnit.NANOSECONDS.toMillis((latencyRange * (i + 1) / latencyBucketFrequencies.length) + minLatency.get()));
                System.err.println(" ms (" + latencyBucketFrequency + ")");
            }
        }

        System.err.print("Messages - Latency Min/Ave/Max = ");
        System.err.print(TimeUnit.NANOSECONDS.toMillis(minLatency.get()) + "/");
        System.err.print(responseCount == 0 ? "-/" : TimeUnit.NANOSECONDS.toMillis(totLatency.get() / responseCount) + "/");
        System.err.println(TimeUnit.NANOSECONDS.toMillis(maxLatency.get()) + " ms");
    }

    private void reset()
    {
        start.set(0L);
        end.set(0L);
        messages.set(0L);
        minLatency.set(Long.MAX_VALUE);
        maxLatency.set(0L);
        totLatency.set(0L);
    }

    private class SubscribeListener implements MessageListener
    {
        public void deliver(Client fromClient, Client toClient, Message message)
        {
            if (Bayeux.META_SUBSCRIBE.equals(message.get(Bayeux.CHANNEL_FIELD)) &&
                (Boolean)message.get(Bayeux.SUCCESSFUL_FIELD))
            {
                toClient.removeListener(this);
                bayeuxClients.add((LoadBayeuxClient)toClient);
            }
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
        private List<Integer> subscriptions = new ArrayList<Integer>();

        private LoadBayeuxClient(HttpClient client, String url)
        {
            super(client, url);
        }

        public void init(String channel, int room)
        {
            subscribe(channel + "/" + room);

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
    }
}
