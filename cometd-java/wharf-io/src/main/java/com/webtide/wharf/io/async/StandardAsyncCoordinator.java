package com.webtide.wharf.io.async;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Selector;
import java.util.concurrent.Executor;

/**
 * @version $Revision$ $Date$
 */
public class StandardAsyncCoordinator implements AsyncCoordinator
{
    private final SelectorManager selector;
    private final Executor threadPool;
    private final Runnable reader = new Reader();
    private final Runnable writer = new Writer();
    private volatile AsyncEndpoint endpoint;
    private volatile AsyncInterpreter interpreter;
    private volatile boolean reading = false;
    private volatile boolean writing = true;

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
        System.out.println("SIMON");

        // TODO: may need to synchronize here
        // because the interpreter can issue a needsRead(true) from the reader thread T1
        // that triggers a selector wakeup (also in T1), that may trigger a readReady() in thread T2
        // before the reader thread T1 is completed; the assertion below will fail.
        //  that gets dispatched before it can return
        // TODO: to solve this issue we must have a single thread that ever reads, not dispatching to a threadpool !
        // TODO: no to the above: 20k connections will have 20k threads waiting to read !
        // But you got the point: it must be safe by design not because you remember to synchronize

        // This method is called from the selector loop thread.
        // We tell the reader processor to read and process the reads
        // Only when it cannot read more, it reschedules itself for
        // read with the selector
        assert !reading;
        reading = true;

        // Remove interest in further reads, otherwise the select loop will
        // continue to notify us that there is data to read
        needsRead(false);

        threadPool.execute(reader);
    }

    public void writeReady()
    {
        assert !writing;
        writing = true;

        needsWrite(false);

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
        writing = false;
        boolean wakeup = endpoint.needsWrite(needsWrite);
        if (wakeup)
            selector.wakeup();
        return wakeup;
    }

    public void readFrom(ByteBuffer buffer)
    {
        assert reading;
        interpreter.readFrom(buffer);
    }

    public void writeFrom(ByteBuffer buffer)
    {
        assert writing;
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
            finally
            {
                reading = false;
            }
        }
    }

    private class Writer implements Runnable
    {
        public void run()
        {
            endpoint.writeFrom(interpreter.getWriteBuffer());
        }
    }
}
