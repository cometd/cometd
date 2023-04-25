package org.cometd.server.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

import org.cometd.bayeux.Promise;
import org.cometd.server.spi.CometDOutput;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

class HandlerCometDOutput implements CometDOutput
{
    private final Response response;

    HandlerCometDOutput(Response response)
    {
        this.response = response;
    }

    @Override
    public void write(byte jsonByte, Promise<Void> promise)
    {
        write(new byte[] {jsonByte}, promise);
    }

    @Override
    public void write(byte[] jsonBytes, Promise<Void> promise)
    {
        response.write(false, ByteBuffer.wrap(jsonBytes), new Callback() {
            @Override
            public void succeeded()
            {
                promise.succeed(null);
            }

            @Override
            public void failed(Throwable x)
            {
                promise.fail(x);
            }
        });
    }

    @Override
    public void close() throws IOException
    {
        CountDownLatch latch = new CountDownLatch(1);
        var callback = new Callback() {
            Throwable failure = null;
            @Override
            public void succeeded()
            {
                latch.countDown();
            }

            @Override
            public void failed(Throwable x)
            {
                failure = x;
                latch.countDown();
            }
        };
        response.write(true, BufferUtil.EMPTY_BUFFER, callback);

        try
        {
            latch.await();
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
        if (callback.failure != null)
            throw new IOException(callback.failure);
    }
}
