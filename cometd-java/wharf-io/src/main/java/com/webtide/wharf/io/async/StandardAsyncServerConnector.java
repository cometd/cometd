package com.webtide.wharf.io.async;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;

import com.webtide.wharf.io.RuntimeIOException;
import com.webtide.wharf.io.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class StandardAsyncServerConnector implements ServerConnector
{
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final AsyncConnectorListener listener;
    private final Executor threadPool;
    private final ServerSocketChannel serverChannel;
    private final SelectorManager selector;

    public StandardAsyncServerConnector(InetSocketAddress address, AsyncConnectorListener listener, Executor threadPool)
    {
        this(address, listener, threadPool, 128, true);
    }

    public StandardAsyncServerConnector(InetSocketAddress address, AsyncConnectorListener listener, Executor threadPool, int backlogSize, boolean reuseAddress)
    {
        try
        {
            this.listener = listener;
            this.threadPool = threadPool;

            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(true);
            serverChannel.socket().setReuseAddress(reuseAddress);
            serverChannel.socket().bind(address, backlogSize);

            selector = new ReadWriteSelectorManager(threadPool);
            threadPool.execute(new AcceptWorker());
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

    public SelectorManager getSelector()
    {
        return selector;
    }

    public void close()
    {
        logger.debug("ServerConnector {} closing", this);
        try
        {
            selector.close();
            serverChannel.close();
            logger.debug("ServerConnector {} closed", this);
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    public int getPort()
    {
        return serverChannel.socket().getLocalPort();
    }

    protected void accepted(SocketChannel channel) throws IOException
    {
        channel.configureBlocking(false);

        AsyncCoordinator coordinator = newCoordinator();

        AsyncEndpoint endpoint = newEndpoint(channel, coordinator);
        coordinator.setEndpoint(endpoint);

        AsyncInterpreter interpreter = listener.connected(coordinator);
        coordinator.setInterpreter(interpreter);

        register(endpoint, SelectionKey.OP_READ, coordinator);
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

    protected class AcceptWorker implements Runnable
    {
        public void run()
        {
            try
            {
                logger.info("ServerConnector {}, acceptor loop entered", this);

                while (serverChannel.isOpen())
                {
                    try
                    {
                        // Do not use the selector for accept() operation, as it is more expensive
                        // (for each new connection needs to return from select, and then accept())
                        SocketChannel socketChannel = serverChannel.accept();
                        logger.debug("ServerConnector {}, accepted socket {}", this, socketChannel);
                        accepted(socketChannel);
                    }
                    catch (SocketTimeoutException x)
                    {
                        logger.debug("ServerConnector {}, ignoring timeout during accept", this);
                    }
                    catch (AsynchronousCloseException x)
                    {
                        logger.debug("ServerConnector {} closed asynchronously", this);
                        break;
                    }
                    catch (IOException x)
                    {
                        close();
                        throw new RuntimeIOException(x);
                    }
                }
            }
            finally
            {
                logger.info("ServerConnector {}, acceptor loop exited", this);
            }
        }
    }
}
