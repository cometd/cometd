package org.cometd.websocket.client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.websocket.Message;
import org.cometd.websocket.WebSocketAsyncInterpreter;
import org.cometd.websocket.WebSocketException;
import org.cometd.websocket.generator.StandardWebSocketGenerator;
import org.cometd.websocket.generator.WebSocketGenerator;
import org.cometd.websocket.parser.WebSocketParser;
import org.cometd.websocket.parser.push.ClientWebSocketPushParser;
import com.webtide.wharf.io.ClientConnector;
import com.webtide.wharf.io.async.AsyncConnectorListener;
import com.webtide.wharf.io.async.AsyncCoordinator;
import com.webtide.wharf.io.async.AsyncInterpreter;
import com.webtide.wharf.io.async.StandardAsyncClientConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class StandardClient implements Client, Session, Listener, AsyncConnectorListener
{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final URI uri;
    private final String protocol;
    private final ClientConnector connector;
    private volatile WebSocketGenerator generator;
    private volatile Client.OpenCallback openCallback;

    public StandardClient(URI uri, String protocol, Executor threadPool)
    {
        this.uri = uri;
        this.protocol = protocol;
        this.connector = StandardAsyncClientConnector.newInstance(new InetSocketAddress(uri.getHost(), uri.getPort()), this, threadPool);
    }

    public Listener.Registration registerListener(final Listener listener)
    {
        listeners.add(listener);
        return new Listener.Registration()
        {
            public void unregister()
            {
                listeners.remove(listener);
            }
        };
    }

    public Session open()
    {
        try
        {
            final AtomicReference<Session> sessionRef = new AtomicReference<Session>();
            final CountDownLatch latch = new CountDownLatch(1);
            open(new OpenCallback()
            {
                public void opened(Session session)
                {
                    sessionRef.set(session);
                    latch.countDown();
                }
            });
            latch.await();
            return sessionRef.get();
        }
        catch (InterruptedException x)
        {
            throw new WebSocketException(x);
        }
    }

    public boolean open(Client.OpenCallback callback)
    {
        if (callback == null)
            throw new IllegalArgumentException();

        // Do not allow pipelined handshakes
        synchronized (this)
        {
            while (openCallback != null)
            {
                try
                {
                    wait();
                }
                catch (InterruptedException x)
                {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            openCallback = callback;
        }
        generator.handshakeRequest(uri, protocol);
        return true;
    }

    public void send(Message message)
    {
        generator.send(message);
    }

    public void close()
    {
        connector.close();
    }

    public void onOpen(Map<String, String> headers)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onOpen(headers);
            }
            catch (Throwable x)
            {
                logger.debug("Exception caught while notifying listener " + listener, x);
            }
        }
        openCallback.opened(this);
        synchronized (this)
        {
            openCallback = null;
            notifyAll();
        }
    }

    public void onMessage(Message message)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onMessage(message);
            }
            catch (Throwable x)
            {
                logger.debug("Exception caught while notifying listener " + listener, x);
            }
        }
    }

    public void onClose()
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onClose();
            }
            catch (Throwable x)
            {
                logger.debug("Exception caught while notifying listener " + listener, x);
            }
        }
    }

    public AsyncInterpreter connected(AsyncCoordinator coordinator)
    {
        WebSocketParser parser = new ClientWebSocketPushParser();
        AsyncInterpreter interpreter = new WebSocketAsyncInterpreter(coordinator, parser, this);
        generator = new StandardWebSocketGenerator(coordinator, interpreter.getWriteBuffer());
        return interpreter;
    }
}
