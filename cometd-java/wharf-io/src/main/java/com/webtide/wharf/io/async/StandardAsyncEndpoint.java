package com.webtide.wharf.io.async;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import com.webtide.wharf.io.RuntimeIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class StandardAsyncEndpoint implements AsyncEndpoint
{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final SocketChannel channel;
    private final AsyncCoordinator coordinator;
    private volatile SelectionKey selectionKey;
    private boolean writePending;

    public StandardAsyncEndpoint(SocketChannel channel, AsyncCoordinator coordinator)
    {
        this.channel = channel;
        this.coordinator = coordinator;
    }

    public void registerWith(Selector selector, int operations, SelectorManager.Listener listener) throws ClosedChannelException
    {
        selectionKey = channel.register(selector, operations, listener);
    }

    public boolean needsRead(boolean needsRead)
    {
        try
        {
            int operations = selectionKey.interestOps();
            if (needsRead)
                selectionKey.interestOps(operations | SelectionKey.OP_READ);
            else
                selectionKey.interestOps(operations & ~SelectionKey.OP_READ);
            int newOperations = selectionKey.interestOps();
            logger.debug("{} operations {} -> {}", new Object[]{this, operations, newOperations});
            return newOperations != 0 && newOperations != operations;
        }
        catch (CancelledKeyException x)
        {
            logger.debug("{} key canceled", this);
            return true;
        }
    }

    public boolean needsWrite(boolean needsWrite)
    {
        try
        {
            int operations = selectionKey.interestOps();
            if (needsWrite)
                selectionKey.interestOps(operations | SelectionKey.OP_WRITE);
            else
                selectionKey.interestOps(operations & ~SelectionKey.OP_WRITE);
            int newOperations = selectionKey.interestOps();
            logger.debug("{} operations {} -> {}", new Object[]{this, operations, newOperations});
            return newOperations != 0 && newOperations != operations;
        }
        catch (CancelledKeyException x)
        {
            logger.debug("{} key canceled", this);
            return true;
        }
    }

    public void readInto(ByteBuffer buffer)
    {
        buffer.clear();
        try
        {
            int read = readAggressively(channel, buffer);
            logger.debug("Read {} into {}", read, buffer);

            if (read > 0)
            {
                buffer.flip();
                coordinator.readFrom(buffer);
            }
            else
            {
                if (channel.isOpen())
                {
                    // We read 0 bytes, we need to re-register for read interest
                    coordinator.needsRead(true);
                }
            }
        }
        catch (ClosedChannelException x)
        {
            logger.debug("Channel closed during read", x);
            close();
        }
        catch (IOException x)
        {
            logger.debug("Unexpected IOException", x);
            close();
        }
    }

    protected int readAggressively(SocketChannel channel, ByteBuffer buffer) throws IOException
    {
        // TODO: make aggressiveness configurable
        int result = 0;
        for (int aggressiveness = 0; aggressiveness < 2; ++aggressiveness)
        {
            int read = this.channel.read(buffer);
            if (read < 0)
            {
                close();
                break;
            }
            else
            {
                result += read;
            }
        }
        return result;
    }

    public void writeFrom(ByteBuffer buffer)
    {
        try
        {
            int written = writeAggressively(channel, buffer);
            logger.debug("Written {} bytes from {}", written, buffer);

            if (buffer.hasRemaining())
            {
                // Suspend the thread
                synchronized (this)
                {
                    // We must issue the needsWrite() below within the sync block, otherwise
                    // another thread can enter writeFrom(), write the whole buffer,
                    // issue a notify that no one is ready to listen and this thread
                    // will wait forever for a notify that already happened.

                    // We wrote less bytes then expected, register for write interest
                    coordinator.needsWrite(true);

                    if (!writePending)
                    {
                        writePending = true;
                        while (writePending)
                        {
                            logger.debug("Partial write, still {} bytes to write, thread {} awaiting full write", buffer.remaining(), Thread.currentThread());
                            wait();
                        }
                        logger.debug("Write completed, thread {} signalled", Thread.currentThread());
                    }
                    else
                    {
                        logger.debug("Partial write, still {} bytes to write", buffer.remaining());
                    }
                }
            }
            else
            {
                // We wrote everything, clear and return
                buffer.clear();

                // Notify writePending thread
                synchronized (this)
                {
                    if (writePending)
                    {
                        writePending = false;
                        notify();
                        logger.debug("Write completed, signalled awaiting thread");
                    }
                }
            }
        }
        catch (InterruptedException x)
        {
            logger.debug("Interrupted during pending write", x);
            close();
        }
        catch (ClosedChannelException x)
        {
            logger.debug("Channel closed during write", x);
            close();
        }
        catch (IOException x)
        {
            logger.debug("Unexpected IOException", x);
            close();
        }
    }

    protected int writeAggressively(SocketChannel channel, ByteBuffer buffer) throws IOException
    {
        // TODO: make aggressiveness configurable
        int result = 0;
        for (int aggressiveness = 0; aggressiveness < 2; ++aggressiveness)
        {
            result += this.channel.write(buffer);
        }
        return result;
    }

    public void close()
    {
        try
        {
            if (selectionKey != null)
                selectionKey.cancel();
            channel.close();
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }
}
