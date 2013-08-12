/*
 * Copyright (c) 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.websocket.server;

import java.io.IOException;
import java.text.ParseException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractWebSocketTransport<S> extends AbstractServerTransport
{
    public static final String PREFIX = "ws";
    public static final String NAME = "websocket";
    public static final String PROTOCOL_OPTION = "protocol";
    public static final String MESSAGES_PER_FRAME_OPTION = "messagesPerFrame";
    public static final String BUFFER_SIZE_OPTION = "bufferSize";
    public static final String MAX_MESSAGE_SIZE_OPTION = "maxMessageSize";
    public static final String IDLE_TIMEOUT_OPTION = "idleTimeout";
    public static final String THREAD_POOL_MAX_SIZE = "threadPoolMaxSize";
    public static final String COMETD_URL_MAPPING = "cometdURLMapping";

    private final ThreadLocal<BayeuxContext> _handshake = new ThreadLocal<>();
    private Executor _executor;
    private ScheduledExecutorService _scheduler;
    private String _protocol = null;
    private int _messagesPerFrame = 1;

    protected AbstractWebSocketTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    protected void init()
    {
        super.init();
        _executor = newExecutor();
        _scheduler = newScheduledExecutor();
        _protocol = getOption(PROTOCOL_OPTION, _protocol);
        _messagesPerFrame = getOption(MESSAGES_PER_FRAME_OPTION, _messagesPerFrame);
    }

    @Override
    protected void destroy()
    {
        _scheduler.shutdownNow();

        Executor threadPool = _executor;
        if (threadPool instanceof ExecutorService)
        {
            ((ExecutorService)threadPool).shutdown();
        }
        else if (threadPool instanceof LifeCycle)
        {
            try
            {
                ((LifeCycle)threadPool).stop();
            }
            catch (Exception x)
            {
                _logger.trace("", x);
            }
        }

        super.destroy();
    }

    protected Executor newExecutor()
    {
        int size = getOption(THREAD_POOL_MAX_SIZE, 64);
        return Executors.newFixedThreadPool(size);
    }

    protected ScheduledExecutorService newScheduledExecutor()
    {
        return Executors.newSingleThreadScheduledExecutor();
    }

    public String getProtocol()
    {
        return _protocol;
    }

    protected boolean checkProtocol(List<String> serverProtocols, List<String> clientProtocols)
    {
        if (serverProtocols.isEmpty())
            return true;

        for (String clientProtocol : clientProtocols)
        {
            if (serverProtocols.contains(clientProtocol))
                return true;
        }
        return false;
    }

    @Override
    public BayeuxContext getContext()
    {
        return _handshake.get();
    }

    protected void handleJSONParseException(S wsSession, ServerSession session, String json, Throwable exception)
    {
        _logger.warn("Error parsing JSON: " + json, exception);
    }

    protected void handleException(S wsSession, ServerSession session, Throwable exception)
    {
        _logger.warn("", exception);
    }

    protected abstract void send(S wsSession, ServerSession session, String data) throws IOException;

    protected void onClose(int code, String reason)
    {
    }

    protected abstract class AbstractWebSocketScheduler implements AbstractServerTransport.Scheduler, Runnable
    {
        protected final Logger _logger = LoggerFactory.getLogger(getClass().getName() + "." + Integer.toHexString(System.identityHashCode(this)));
        private final AtomicBoolean _scheduling = new AtomicBoolean();
        private final BayeuxContext _context;
        private volatile ServerSessionImpl _session;
        private ServerMessage.Mutable _connectReply;
        private ScheduledFuture<?> _connectTask;

        protected AbstractWebSocketScheduler(BayeuxContext context)
        {
            _context = context;
        }

        protected void send(S wsSession, List<ServerMessage> messages) throws IOException
        {
            if (messages.isEmpty())
                return;

            // Under load, it is possible that we have many bayeux messages and
            // that these would generate a large websocket message that the client
            // could not handle, so we need to split the messages into batches.

            int count = messages.size();
            int batchSize = _messagesPerFrame > 0 ? Math.min(_messagesPerFrame, count) : count;
            // Assume 4 fields of 32 chars per message
            int capacity = batchSize * 4 * 32;
            StringBuilder builder = new StringBuilder(capacity);

            int index = 0;
            while (index < count)
            {
                builder.setLength(0);
                builder.append("[");
                int batch = Math.min(batchSize, count - index);
                for (int b = 0; b < batch; ++b)
                {
                    if (b > 0)
                        builder.append(",");
                    ServerMessage serverMessage = messages.get(index + b);
                    builder.append(serverMessage.getJSON());
                }
                builder.append("]");
                index += batch;
                AbstractWebSocketTransport.this.send(wsSession, _session, builder.toString());
            }
        }

        protected void send(S session, ServerMessage message) throws IOException
        {
            StringBuilder builder = new StringBuilder(message.size() * 32);
            builder.append("[").append(message.getJSON()).append("]");
            AbstractWebSocketTransport.this.send(session, _session, builder.toString());
        }

        protected void onClose(int code, String reason)
        {
            final ServerSessionImpl session = _session;
            if (session != null)
            {
                // There is no need to call BayeuxServerImpl.removeServerSession(),
                // because the connection may have been closed for a reload, so
                // just null out the current session to have it retrieved again
                _session = null;
                session.startIntervalTimeout(getInterval());
                cancelMetaConnectTask(session);
            }
            _logger.debug("Closing {}/{}", code, reason);
            AbstractWebSocketTransport.this.onClose(code, reason);
        }

        protected void onError(Throwable failure)
        {
            _logger.info("WebSocket Error", failure);
        }

        private boolean cancelMetaConnectTask(ServerSessionImpl session)
        {
            final ScheduledFuture<?> connectTask;
            synchronized (session.getLock())
            {
                connectTask = _connectTask;
                _connectTask = null;
            }
            if (connectTask == null)
                return false;
            _logger.debug("Cancelling meta connect task {}", connectTask);
            connectTask.cancel(false);
            return true;
        }

        protected void onMessage(S wsSession, String data)
        {
            _handshake.set(_context);
            getBayeux().setCurrentTransport(AbstractWebSocketTransport.this);
            try
            {
                ServerMessage.Mutable[] messages = parseMessages(data);
                _logger.debug("Received messages {}", data);
                for (ServerMessage.Mutable message : messages)
                    onMessage(wsSession, message);
            }
            catch (ParseException x)
            {
                handleJSONParseException(wsSession, _session, data, x);
            }
            catch (Exception x)
            {
                handleException(wsSession, _session, x);
            }
            finally
            {
                _handshake.set(null);
                getBayeux().setCurrentTransport(null);
            }
        }

        protected void onMessage(S wsSession, ServerMessage.Mutable message) throws IOException
        {
            _logger.debug("Received {}", message);
            boolean connect = Channel.META_CONNECT.equals(message.getChannel());

            // Get the session from the message
            ServerSessionImpl session = _session;
            String clientId = message.getClientId();
            if (session == null || !session.getId().equals(clientId))
            {
                session = (ServerSessionImpl)getBayeux().getSession(message.getClientId());
                _session = session;
            }

            // Session expired concurrently ?
            if (session != null && !session.isHandshook())
            {
                session = null;
                _session = session;
            }

            // Remember the connected status
            boolean wasConnected = session != null && session.isConnected();

            // Delegate to BayeuxServer to handle the message.
            // This may trigger server-side listeners that may add messages
            // to the queue, which will trigger a schedule() via flush()

            ServerMessage.Mutable reply = getBayeux().handle(session, message);
            if (reply != null)
            {
                if (session == null)
                {
                    session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());
                    if (session != null)
                        session.setScheduler(this);
                }

                List<ServerMessage> queue = null;

                // Check again if session is connected, BayeuxServer.handle() may have caused a disconnection
                if (connect && reply.isSuccessful() && session != null && session.isConnected())
                {
                    // We need to set the scheduler again, in case the connection
                    // has temporarily broken and we have created a new scheduler
                    session.setScheduler(this);

                    // If we deliver only via meta connect, and we have messages,
                    // we need to send the queue and the meta connect reply
                    boolean metaConnectDelivery = isMetaConnectDeliveryOnly() || session.isMetaConnectDeliveryOnly();
                    boolean hasMessages = session.hasNonLazyMessages();
                    boolean replyToMetaConnect = hasMessages && metaConnectDelivery;
                    if (replyToMetaConnect)
                    {
                        queue = session.takeQueue();
                    }
                    else
                    {
                        long timeout = session.calculateTimeout(getTimeout());
                        boolean holdMetaConnect = timeout > 0 && wasConnected;
                        if (holdMetaConnect)
                        {
                            // Decide atomically if we need to hold the meta connect or not
                            // In schedule() we decide atomically if reply to the meta connect
                            synchronized (session.getLock())
                            {
                                if (!session.hasNonLazyMessages())
                                {
                                    if (cancelMetaConnectTask(session))
                                        _logger.debug("Cancelled unresponded meta connect {}", _connectReply);

                                    _connectReply = reply;

                                    // Delay the connect reply until timeout.
                                    long expiration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + timeout;
                                    _connectTask = _scheduler.schedule(new MetaConnectReplyTask(reply, expiration), timeout, TimeUnit.MILLISECONDS);
                                    _logger.debug("Scheduled meta connect {}", _connectTask);
                                    reply = null;
                                }
                            }
                            if (reply != null)
                                queue = session.takeQueue();
                        }
                    }
                }

                // Send the reply
                if (reply != null)
                {
                    try
                    {
                        if (queue != null)
                            send(wsSession, queue);
                    }
                    finally
                    {
                        // Start the interval timeout before sending the reply to
                        // avoid race conditions, and even if sending the queue
                        // throws an exception so that we can sweep the session
                        if (connect && session != null)
                        {
                            if (session.isConnected())
                                session.startIntervalTimeout(getInterval());
                            else if (session.isDisconnected())
                                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                        }
                    }

                    reply = getBayeux().extendReply(session, session, reply);

                    if (reply != null)
                    {
                        getBayeux().freeze(reply);
                        send(wsSession, reply);
                    }
                }
            }
        }

        protected abstract void close(int code, String reason);

        public void cancel()
        {
            final ServerSessionImpl session = _session;
            if (session != null)
            {
                if (cancelMetaConnectTask(session))
                    close(1000, "Cancel");
            }
        }

        public void schedule()
        {
            // This method may be called concurrently, for example when 2 clients
            // publish concurrently on the same channel.
            // We must avoid to dispatch multiple times, to save threads.
            // However, the CAS operation introduces a window where a schedule()
            // is skipped and the queue may remain full; to avoid this situation,
            // we reschedule at the end of schedule(boolean, ServerMessage.Mutable).
            if (_scheduling.compareAndSet(false, true))
                _executor.execute(this);
        }

        public void run()
        {
            schedule(false, null);
        }

        protected abstract void schedule(boolean timeout, ServerMessage.Mutable expiredConnectReply);

        protected void schedule(S wsSession, boolean timeout, ServerMessage.Mutable expiredConnectReply)
        {
            // This method may be executed concurrently by a thread triggered by
            // schedule() and by the timeout thread that replies to the meta connect.

            boolean reschedule = false;
            ServerSessionImpl session = _session;
            try
            {
                if (session == null)
                {
                    AbstractWebSocketTransport.this._logger.debug("No session, skipping reply {}", expiredConnectReply);
                    return;
                }

                // Decide atomically if we have to reply to the meta connect
                // We need to guarantee the metaConnectDeliverOnly semantic
                // and allow only one thread to reply to the meta connect
                // otherwise we may have out of order delivery.
                boolean metaConnectDelivery = isMetaConnectDeliveryOnly() || session.isMetaConnectDeliveryOnly();
                boolean reply = false;
                ServerMessage.Mutable connectReply;
                synchronized (session.getLock())
                {
                    connectReply = _connectReply;

                    if (timeout && connectReply != expiredConnectReply)
                    {
                        // We had a second meta connect arrived while we were expiring the first:
                        // just ignore to reply to the first connect as if we were able to cancel it
                        _logger.debug("Flushing skipped replies that do not match: {} != {}", connectReply, expiredConnectReply);
                        return;
                    }

                    if (connectReply == null)
                    {
                        if (metaConnectDelivery)
                        {
                            // If we need to deliver only via meta connect, but we
                            // do not have one outstanding, wait until it arrives
                            _logger.debug("Flushing skipped since metaConnectDelivery={}, metaConnectReply={}", metaConnectDelivery, connectReply);
                            return;
                        }
                    }
                    else
                    {
                        if (timeout || metaConnectDelivery || !session.isConnected())
                        {
                            // We will reply to the meta connect, so cancel the timeout task
                            cancelMetaConnectTask(session);
                            _connectReply = null;
                            reply = true;
                        }
                    }
                }

                reschedule = true;
                List<ServerMessage> queue = session.takeQueue();

                try
                {
                    _logger.debug("Flushing {} timeout={} metaConnectDelivery={}, metaConnectReply={}, messages={}", session, timeout, metaConnectDelivery, reply, queue);
                    send(wsSession, queue);
                }
                finally
                {
                    if (reply)
                    {
                        // Start the interval timeout before sending the reply to
                        // avoid race conditions, and even if sending the queue
                        // throws an exception so that we can sweep the session
                        if (session.isConnected())
                            session.startIntervalTimeout(getInterval());
                        else if (session.isDisconnected())
                            connectReply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                    }
                }

                if (reply)
                {
                    connectReply = getBayeux().extendReply(session, session, connectReply);

                    if (connectReply != null)
                    {
                        getBayeux().freeze(connectReply);
                        send(wsSession, connectReply);
                    }
                }
            }
            catch (Exception x)
            {
                handleException(wsSession, session, x);
            }
            finally
            {
                if (!timeout)
                    _scheduling.compareAndSet(true, false);

                if (reschedule && session.hasNonLazyMessages())
                    schedule();
            }
        }

        private class MetaConnectReplyTask implements Runnable
        {
            private final ServerMessage.Mutable _connectReply;
            private final long _connectExpiration;

            private MetaConnectReplyTask(ServerMessage.Mutable connectReply, long connectExpiration)
            {
                this._connectReply = connectReply;
                this._connectExpiration = connectExpiration;
            }

            public void run()
            {
                long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                long delay = now - _connectExpiration;
                if (delay > 5000) // TODO: make the max delay a parameter ?
                    AbstractWebSocketTransport.this._logger.debug("/meta/connect {} expired {} ms too late", _connectReply, delay);

                // Send the meta connect response after timeout.
                // We *must* execute the next schedule() otherwise
                // the client will timeout the meta connect, so we
                // do not care about flipping the _scheduling field.
                schedule(true, _connectReply);
            }
        }
    }
}
