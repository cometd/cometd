package org.cometd.bwtp.server;

import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import org.cometd.bwtp.BWTPActionType;
import org.cometd.bwtp.BWTPChannel;
import org.cometd.bwtp.BWTPHeaderFrame;
import org.cometd.bwtp.BWTPListener;
import org.cometd.bwtp.BWTPMessageFrame;
import org.cometd.bwtp.IBWTPConnection;
import org.cometd.bwtp.StandardBWTPChannel;
import org.cometd.bwtp.StandardBWTPConnectionHandler;
import org.cometd.bwtp.generator.BWTPGenerator;
import org.cometd.bwtp.parser.BWTPParser;
import org.cometd.wharf.async.AsyncCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class ServerBWTPConnection implements IBWTPConnection, BWTPListener
{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ConcurrentMap<String, BWTPChannel> channels = new ConcurrentHashMap<String, BWTPChannel>();
    private final List<BWTPListener> listeners = new CopyOnWriteArrayList<BWTPListener>();
    private final AsyncCoordinator coordinator;
    private final BWTPParser parser;
    private final BWTPGenerator generator;

    public ServerBWTPConnection(AsyncCoordinator coordinator, BWTPParser parser, BWTPGenerator generator)
    {
        this.coordinator = coordinator;
        this.parser = parser;
        this.generator = generator;
        this.parser.addListener(new StandardBWTPConnectionHandler(this, this));
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
        String channelId = generator.open(path, headers, false);
        BWTPChannel channel = new StandardBWTPChannel(channelId, ServerBWTPConnection.this, generator);
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
        coordinator.close();
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
            coordinator.close();
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
            coordinator.close();
            throw new ClosedByInterruptException();
        }
    }
}
