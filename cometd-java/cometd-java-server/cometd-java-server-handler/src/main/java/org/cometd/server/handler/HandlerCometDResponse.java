package org.cometd.server.handler;

import java.nio.ByteBuffer;

import org.cometd.bayeux.Promise;
import org.cometd.server.spi.CometDOutput;
import org.cometd.server.spi.CometDResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

class HandlerCometDResponse implements CometDResponse
{
    private final Response response;
    private CometDOutput cometDOutput;

    public HandlerCometDResponse(Response response)
    {
        this.response = response;
    }

    @Override
    public void addHeader(String name, String value)
    {
        response.getHeaders().add(name, value);
    }

    @Override
    public String getCharacterEncoding()
    {
        String[] split = response.getHeaders().get(HttpHeader.CONTENT_TYPE).split(";");
        if (split.length == 2)
            return split[1].split("=")[1];
        return "iso-8859-1";
    }

    @Override
    public CometDOutput getOutput()
    {
        if (cometDOutput == null)
        {
            cometDOutput = new CometDOutput()
            {
                @Override
                public void write(byte jsonByte, Promise<Void> promise)
                {
                    write(new byte[]{jsonByte}, promise);
                }

                @Override
                public void write(byte[] jsonBytes, Promise<Void> promise)
                {
                    response.write(false, ByteBuffer.wrap(jsonBytes), new Callback()
                    {
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
                public void close()
                {
                    response.write(true, BufferUtil.EMPTY_BUFFER, Callback.NOOP);
                }
            };
        }
        return cometDOutput;
    }

    @Override
    public void setContentType(String contentType)
    {
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
    }
}
