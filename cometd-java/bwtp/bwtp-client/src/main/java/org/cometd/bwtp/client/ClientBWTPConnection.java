package org.cometd.bwtp.client;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import org.cometd.bwtp.BWTPActionType;
import org.cometd.bwtp.BWTPChannel;
import org.cometd.bwtp.BWTPHeaderFrame;
import org.cometd.bwtp.BWTPListener;
import org.cometd.bwtp.BWTPMessageFrame;
import org.cometd.bwtp.BWTPVersionType;
import org.cometd.bwtp.IBWTPConnection;
import org.cometd.bwtp.StandardBWTPChannel;
import org.cometd.bwtp.StandardBWTPConnectionHandler;
import org.cometd.bwtp.client.generator.ClientBWTPGenerator;
import org.cometd.bwtp.client.generator.StandardClientBWTPGenerator;
import org.cometd.bwtp.client.parser.push.ClientBWTPPushParser;
import org.cometd.bwtp.parser.BWTPParser;
import org.cometd.wharf.ClientConnector;
import org.cometd.wharf.async.AsyncConnectorListener;
import org.cometd.wharf.async.AsyncCoordinator;
import org.cometd.wharf.async.AsyncInterpreter;
import org.cometd.wharf.async.StandardAsyncClientConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * // TODO: for every catch (ClosedChannelException) I should add a connector.close() ?
 * @version $Revision$ $Date$
 */
public class ClientBWTPConnection implements AsyncConnectorListener, IBWTPConnection, BWTPListener
{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ConcurrentMap<String, BWTPChannel> channels = new ConcurrentHashMap<String, BWTPChannel>();
    private final List<BWTPListener> listeners = new CopyOnWriteArrayList<BWTPListener>();
    private final InetSocketAddress address;
    private final String path;
    private final Map<String, String> headers;
    private final ClientConnector connector;
    private volatile BWTPParser parser;
    private volatile ClientBWTPGenerator generator;
    private volatile CountDownLatch upgradeLatch;

    public ClientBWTPConnection(InetSocketAddress address, String path, Map<String, String> headers, Executor threadPool)
    {
        if (!path.startsWith("/"))
            throw new IllegalArgumentException();

        this.address = address;
        this.path = path;
        this.headers = headers;
        connector = new StandardAsyncClientConnector(this, threadPool);
    }

    public void connect() throws ConnectException, ClosedChannelException
    {
        connector.connect(address);

        Map<String, String> upgradeHeaders = headers == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(headers);
        upgradeHeaders.put("Upgrade", BWTPVersionType.BWTP_1_0.getCode());
        upgradeHeaders.put("Connection", "upgrade");
        if (!upgradeHeaders.containsKey("Host"))
            upgradeHeaders.put("Host", address.getHostName() + ":" + address.getPort());

        upgradeLatch = new CountDownLatch(1);
        generator.upgradeRequest(path, upgradeHeaders);
        await(upgradeLatch);
    }

    public AsyncInterpreter connected(AsyncCoordinator coordinator)
    {
        parser = new ClientBWTPPushParser()
        {
            @Override
            protected void upgradeResponse(String version, int code, String message, Map<String, String> headers)
            {
                ClientBWTPConnection.this.upgradeResponse(version, code, message, headers);
            }
        };
        parser.addListener(new StandardBWTPConnectionHandler(this, this));
        AsyncInterpreter interpreter = new ClientBWTPAsyncInterpreter(coordinator, parser);
        generator = new StandardClientBWTPGenerator(coordinator, interpreter.getWriteBuffer(), path);
        return interpreter;
    }

    protected void upgradeResponse(String version, int code, String message, Map<String, String> headers)
    {
        logger.debug("Interpreted upgrade response\r\n{} {} {}\r\n{}", new Object[]{version, code, message, headers});
        // TODO if not 101, notify protocol error
        upgradeLatch.countDown();
    }

    public void addListener(BWTPListener listener)
    {
        listeners.add(listener);
    }

    public void removeListener(BWTPListener listener)
    {
        listeners.remove(listener);
    }

    public BWTPChannel open(String path, Map<String, String> headers) throws ClosedChannelException
    {
        final CountDownLatch openLatch = new CountDownLatch(1);
        BWTPParser.Listener listener = new BWTPParser.Listener.Adapter()
        {
            @Override
            public void onHeaderFrame(BWTPHeaderFrame frame)
            {
                if (frame.getAction() == BWTPActionType.OPENED)
                {
                    openLatch.countDown();
                }
            }
        };
        parser.addListener(listener);
        String channelId = generator.open(path, headers, true);
        BWTPChannel channel = new StandardBWTPChannel(channelId, ClientBWTPConnection.this, generator);
        channels.put(channelId, channel);
        await(openLatch);
        parser.removeListener(listener);
        return findChannel(channelId);
    }

    public void opened(String channelId, Map<String, String> headers)
    {
        try
        {
            BWTPChannel channel = new StandardBWTPChannel(channelId, this, generator);
            channels.put(channelId, channel);
            generator.opened(channelId, headers);
        }
        catch (ClosedChannelException x)
        {
            logger.debug("Could not write OPENED frame because the connection was closed", x);
        }
    }

    public BWTPChannel findChannel(String channelId)
    {
        return channels.get(channelId);
    }

    public void close(String channelId, Map<String, String> headers)
    {
        channels.remove(channelId);
        try
        {
            generator.close(channelId, headers);
        }
        catch (ClosedChannelException x)
        {
            logger.debug("Could not write CLOSE frame because the connection was closed", x);
        }
    }

    public void close(Map<String, String> headers)
    {
        channels.clear();
        try
        {
            generator.close(BWTPChannel.ALL_ID, headers);
        }
        catch (ClosedChannelException x)
        {
            logger.debug("Could not write CLOSE frame because the connection was closed", x);
        }
        connector.close();
    }

    public void closed(String channelId, Map<String, String> headers)
    {
        if (BWTPChannel.ALL_ID.equals(channelId))
        {
            channels.clear();
            try
            {
                generator.closed(channelId, headers);
            }
            catch (ClosedChannelException x)
            {
                logger.debug("Could not write CLOSED frame because the connection was closed", x);
            }
            connector.close();
        }
        else
        {
            channels.remove(channelId);
            try
            {
                generator.closed(channelId, headers);
            }
            catch (ClosedChannelException x)
            {
                logger.debug("Could not write CLOSED frame because the connection was closed", x);
            }
        }
    }

    public Map<String, String> onOpen(BWTPHeaderFrame frame)
    {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (BWTPListener listener : listeners)
        {
            try
            {
                Map<String, String> headers = listener.onOpen(frame);
                if (headers != null)
                    result.putAll(headers);
            }
            catch (Exception x)
            {
                logger.debug("Exception while calling listener " + listener, x);
            }
        }
        return result;
    }

    public void onHeader(BWTPChannel channel, BWTPHeaderFrame frame)
    {
        for (BWTPListener listener : listeners)
        {
            try
            {
                listener.onHeader(channel, frame);
            }
            catch (Exception x)
            {
                logger.debug("Exception while calling listener " + listener, x);
            }
        }
    }

    public void onMessage(BWTPChannel channel, BWTPMessageFrame frame)
    {
        for (BWTPListener listener : listeners)
        {
            try
            {
                listener.onMessage(channel, frame);
            }
            catch (Exception x)
            {
                logger.debug("Exception while calling listener " + listener, x);
            }
        }
    }

    public Map<String, String> onClose(BWTPChannel channel, BWTPHeaderFrame frame)
    {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (BWTPListener listener : listeners)
        {
            try
            {
                Map<String, String> headers = listener.onClose(channel, frame);
                if (headers != null)
                    result.putAll(headers);
            }
            catch (Exception x)
            {
                logger.debug("Exception while calling listener " + listener, x);
            }
        }
        return result;
    }

    private void await(CountDownLatch latch) throws ClosedByInterruptException
    {
        try
        {
            // TODO: add timeout
            latch.await();
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            connector.close();
            throw new ClosedByInterruptException();
        }
    }
}
