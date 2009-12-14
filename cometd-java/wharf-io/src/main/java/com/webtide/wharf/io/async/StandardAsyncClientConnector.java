package com.webtide.wharf.io.async;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;

import com.webtide.wharf.io.ClientConnector;
import com.webtide.wharf.io.RuntimeIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class StandardAsyncClientConnector implements ClientConnector
{
    public static StandardAsyncClientConnector newInstance(InetSocketAddress address, AsyncConnectorListener listener, Executor threadPool)
    {
        StandardAsyncClientConnector result = new StandardAsyncClientConnector(listener, threadPool);
        result.connect(address);
        return result;
    }

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final AsyncConnectorListener listener;
    private final Executor threadPool;
    private final SelectorManager selector;
    private final SocketChannel channel;

    protected StandardAsyncClientConnector(AsyncConnectorListener listener, Executor threadPool)
    {
        try
        {
            this.listener = listener;
            this.threadPool = threadPool;
            selector = new ReadWriteSelectorManager(threadPool);
            channel = SocketChannel.open();
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    protected void connect(InetSocketAddress address)
    {
        try
        {
            logger.debug("ClientConnector {} connecting to {}", this, address);
            channel.connect(address);
            logger.debug("ClientConnector {} connected to {}", this, address);
            connected(channel);
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    protected void connected(SocketChannel channel) throws IOException
    {
        channel.configureBlocking(false);

        AsyncCoordinator coordinator = newCoordinator();

        AsyncEndpoint endpoint = newEndpoint(channel, coordinator);
        coordinator.setEndpoint(endpoint);

        AsyncInterpreter interpreter = listener.connected(coordinator);
        coordinator.setInterpreter(interpreter);

        register(endpoint, SelectionKey.OP_READ, coordinator);
    }

    public void close()
    {
        logger.debug("ClientConnector {} closing", this);
        try
        {
            selector.close();
            channel.close();
            logger.debug("ClientConnector {} closed", this);
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    protected Executor getThreadPool()
    {
        return threadPool;
    }

    protected AsyncCoordinator newCoordinator()
    {
        return new StandardAsyncCoordinator(selector, getThreadPool());
    }

    protected AsyncEndpoint newEndpoint(SocketChannel channel, AsyncCoordinator coordinator)
    {
        return new StandardAsyncEndpoint(channel, coordinator);
    }

    protected void register(AsyncEndpoint endpoint, int operations, AsyncCoordinator coordinator)
    {
        selector.register(endpoint, operations, coordinator);
    }
}
