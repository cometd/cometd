package org.cometd.websocket.client;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import org.cometd.websocket.Message;
import org.cometd.websocket.WebSocketAsyncInterpreter;
import org.cometd.websocket.generator.StandardWebSocketGenerator;
import org.cometd.websocket.generator.WebSocketGenerator;
import org.cometd.websocket.parser.WebSocketParser;
import org.cometd.websocket.parser.push.ClientWebSocketPushParser;
import org.cometd.wharf.ClientConnector;
import org.cometd.wharf.async.AsyncConnectorListener;
import org.cometd.wharf.async.AsyncCoordinator;
import org.cometd.wharf.async.AsyncInterpreter;
import org.cometd.wharf.async.StandardAsyncClientConnector;

/**
 * @version $Revision$ $Date$
 */
public class StandardClient extends AbstractClient implements AsyncConnectorListener
{
    private final URI uri;
    private final String protocol;
    private final ClientConnector connector;
    private volatile State state = State.CLOSED;
    private volatile WebSocketGenerator generator;

    public StandardClient(URI uri, String protocol, Executor threadPool)
    {
        this.uri = uri;
        this.protocol = protocol;
        this.connector = new StandardAsyncClientConnector(this, threadPool);
    }

    public boolean open()
    {
        try
        {
            final CountDownLatch latch = new CountDownLatch(1);
            open(new OpenCallback()
            {
                public void opened(Session session)
                {
                    latch.countDown();
                }
            });
            latch.await();
            return state == State.OPENED;
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void open(final OpenCallback callback)
    {
        if (state != State.CLOSED)
            throw new IllegalStateException();

        if (callback == null)
            throw new IllegalArgumentException();

        try
        {
            connector.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));

            state = State.OPENING;

            addListener(new Listener.Adapter()
            {
                @Override
                public void onOpen(Map<String, String> headers)
                {
                    removeListener(this);
                    callback.opened(StandardClient.this);
                }

                @Override
                public void onClose()
                {
                    removeListener(this);
                }
            });

            generator.handshakeRequest(uri, protocol);
        }
        catch (ConnectException x)
        {
            notifyConnectException(x);
            close();
        }
        catch (ClosedChannelException x)
        {
            notifyException(x);
            close();
        }
    }

    public void send(Message message)
    {
        if (state != State.OPENED)
            throw new IllegalStateException();

        try
        {
            generator.send(message);
        }
        catch (ClosedChannelException x)
        {
            notifyException(x);
            close();
        }
    }

    public void close()
    {
        connector.close();
        state = State.CLOSED;
        notifyClose();
    }

    public AsyncInterpreter connected(AsyncCoordinator coordinator)
    {
        WebSocketParser parser = new ClientWebSocketPushParser();
        AsyncInterpreter interpreter = new WebSocketAsyncInterpreter(coordinator, parser, new ProtocolListener());
        generator = new StandardWebSocketGenerator(coordinator, interpreter.getWriteBuffer());
        return interpreter;
    }

    private enum State
    {
        OPENING, OPENED, CLOSED
    }

    private class ProtocolListener extends Listener.Adapter
    {
        @Override
        public void onOpen(Map<String, String> headers)
        {
            state = State.OPENED;
            notifyOpen(headers);
        }

        @Override
        public void onMessage(Message message)
        {
            notifyMessage(message);
        }

        @Override
        public void onProtocolError()
        {
            notifyProtocolError();
            close();
        }
    }
}
