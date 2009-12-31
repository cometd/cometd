package org.cometd.wharf.async;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;

import org.cometd.wharf.ClientConnector;
import org.cometd.wharf.RuntimeIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class StandardAsyncClientConnector implements ClientConnector
{
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final AsyncConnectorListener listener;
    private final Executor threadPool;
    private final SelectorManager selector;
    private final SocketChannel channel;
    private volatile AsyncCoordinator coordinator;

    public StandardAsyncClientConnector(AsyncConnectorListener listener, Executor threadPool)
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

    public void connect(InetSocketAddress address) throws ConnectException
    {
        try
        {
            logger.debug("ClientConnector {} connecting to {}", this, address);
            channel.socket().connect(address); // TODO: add timeout
            logger.debug("ClientConnector {} connected to {}", this, address);
            connected(channel);
        }
        catch (AlreadyConnectedException x)
        {
            throw new IllegalStateException(x);
        }
        catch (ConnectException x)
        {
            throw x;
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    protected void connected(SocketChannel channel) throws IOException
    {
        channel.configureBlocking(false);

        coordinator = newCoordinator();

        AsyncEndpoint endpoint = newEndpoint(channel, coordinator);
        coordinator.setEndpoint(endpoint);

        AsyncInterpreter interpreter = listener.connected(coordinator);
        coordinator.setInterpreter(interpreter);

        register(endpoint, SelectionKey.OP_READ, coordinator);
    }

    public void close()
    {
        logger.debug("ClientConnector {} closing", this);
        if (coordinator != null)
            coordinator.close();
        selector.close();
        logger.debug("ClientConnector {} closed", this);
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
