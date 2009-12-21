package com.webtide.wharf.io.async;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class StandardAsyncCoordinator implements AsyncCoordinator
{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final SelectorManager selector;
    private final Executor threadPool;
    private final Runnable reader = new Reader();
    private final Runnable writer = new Writer();
    private volatile AsyncEndpoint endpoint;
    private volatile AsyncInterpreter interpreter;

    public StandardAsyncCoordinator(SelectorManager selector, Executor threadPool)
    {
        this.selector = selector;
        this.threadPool = threadPool;
    }

    public void setEndpoint(AsyncEndpoint endpoint)
    {
        this.endpoint = endpoint;
    }

    public void setInterpreter(AsyncInterpreter interpreter)
    {
        this.interpreter = interpreter;
    }

    public void readReady()
    {
        // Remove interest in further reads, otherwise the select loop will
        // continue to notify us that it is ready to read
        needsRead(false);
        // Dispatch the read to another thread
        threadPool.execute(reader);
    }

    public void writeReady()
    {
        // Remove interest in further writes, otherwise the select loop will
        // continue to notify us that it is ready to write
        needsWrite(false);
        // Dispatch the write to another thread
        threadPool.execute(writer);
    }

    public boolean needsRead(boolean needsRead)
    {
        boolean wakeup = endpoint.needsRead(needsRead);
        if (wakeup)
            selector.wakeup();
        return wakeup;
    }

    public boolean needsWrite(boolean needsWrite)
    {
        boolean wakeup = endpoint.needsWrite(needsWrite);
        if (wakeup)
            selector.wakeup();
        return wakeup;
    }

    public void readFrom(ByteBuffer buffer)
    {
        interpreter.readFrom(buffer);
    }

    public void writeFrom(ByteBuffer buffer) throws ClosedChannelException
    {
        endpoint.writeFrom(buffer);
    }

    private class Reader implements Runnable
    {
        public void run()
        {
            try
            {
                endpoint.readInto(interpreter.getReadBuffer());
            }
            catch (ClosedChannelException x)
            {
                logger.debug("Could not read, endpoint has been closed", x);
            }
        }
    }

    private class Writer implements Runnable
    {
        public void run()
        {
            try
            {
                endpoint.writeFrom(interpreter.getWriteBuffer());
            }
            catch (ClosedChannelException x)
            {
                logger.debug("Could not write, endpoint has been closed", x);
            }
        }
    }
}
