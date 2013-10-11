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

package org.cometd.server.transport;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;

/**
 * Abstract Long Polling Transport.
 * <p/>
 * Transports based on this class can be configured with servlet init parameters:
 * <dl>
 * <dt>browserId</dt><dd>The Cookie name used to save a browser ID.</dd>
 * <dt>maxSessionsPerBrowser</dt><dd>The maximum number of long polling sessions allowed per browser.</dd>
 * <dt>multiSessionInterval</dt><dd>The polling interval to use once max session per browser is exceeded.</dd>
 * <dt>autoBatch</dt><dd>If true a batch will be automatically created to span the handling of messages received from a session.</dd>
 * <dt>allowMultiSessionsNoBrowser</dt><dd>Allows multiple sessions even when the browser identifier cannot be retrieved.</dd>
 * </dl>
 */
public abstract class LongPollingTransport extends HttpTransport
{
    protected LongPollingTransport(BayeuxServerImpl bayeux, String name)
    {
        super(bayeux, name);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        getBayeux().setCurrentTransport(this);
        setCurrentRequest(request);
        try
        {
            process(request, response);
        }
        finally
        {
            setCurrentRequest(null);
            getBayeux().setCurrentTransport(null);
        }
    }

    protected void process(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // Is this a resumed connect?
        LongPollScheduler scheduler = (LongPollScheduler)request.getAttribute(LongPollScheduler.ATTRIBUTE);
        if (scheduler == null)
        {
            // No - process messages

            // Remember if we start a batch
            boolean batch = false;

            // Don't know the session until first message or handshake response.
            ServerSessionImpl session = null;
            boolean connect = false;

            try
            {
                ServerMessage.Mutable[] messages = parseMessages(request);
                if (messages == null)
                    return;

                PrintWriter writer = null;
                for (ServerMessage.Mutable message : messages)
                {
                    // Is this a connect?
                    connect = Channel.META_CONNECT.equals(message.getChannel());

                    // Get the session from the message
                    String client_id = message.getClientId();
                    if (session == null || client_id != null && !client_id.equals(session.getId()))
                    {
                        session = (ServerSessionImpl)getBayeux().getSession(client_id);
                        if (isAutoBatch() && !batch && session != null && !connect && !message.isMeta())
                        {
                            // start a batch to group all resulting messages into a single response.
                            batch = true;
                            session.startBatch();
                        }
                    }
                    else if (!session.isHandshook())
                    {
                        batch = false;
                        session = null;
                    }

                    if (connect && session != null)
                    {
                        // cancel previous scheduler to cancel any prior waiting long poll
                        // this should also dec the browser ID
                        session.setScheduler(null);
                    }

                    boolean wasConnected = session != null && session.isConnected();

                    // Forward handling of the message.
                    // The actual reply is return from the call, but other messages may
                    // also be queued on the session.
                    ServerMessage.Mutable reply = bayeuxServerHandle(session, message);

                    // Do we have a reply ?
                    if (reply != null)
                    {
                        if (session == null)
                        {
                            // This must be a handshake, extract a session from the reply
                            session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());

                            // Add the browser ID cookie
                            if (session != null)
                            {
                                String browserId = findBrowserId(request);
                                if (browserId == null)
                                    setBrowserId(request, response);
                            }
                        }
                        else
                        {
                            // Special handling for connect
                            if (connect)
                            {
                                try
                                {
                                    if (!session.hasNonLazyMessages() && reply.isSuccessful())
                                    {
                                        // Detect if we have multiple sessions from the same browser
                                        // Note that CORS requests do not send cookies, so we need to handle them specially
                                        // CORS requests always have the Origin header

                                        String browserId = findBrowserId(request);
                                        boolean allowSuspendConnect;
                                        if (browserId != null)
                                            allowSuspendConnect = incBrowserId(browserId);
                                        else
                                            allowSuspendConnect = isAllowMultiSessionsNoBrowser() || request.getHeader("Origin") != null;

                                        if (allowSuspendConnect)
                                        {
                                            long timeout = session.calculateTimeout(getTimeout());

                                            // Support old clients that do not send advice:{timeout:0} on the first connect
                                            if (timeout > 0 && wasConnected && session.isConnected())
                                            {
                                                // Between the last time we checked for messages in the queue
                                                // (which was false, otherwise we would not be in this branch)
                                                // and now, messages may have been added to the queue.
                                                // We will suspend anyway, but setting the scheduler on the
                                                // session will decide atomically if we need to resume or not.

                                                // Suspend and wait for messages
                                                AsyncContext asyncContext = request.startAsync();
                                                asyncContext.setTimeout(timeout);
                                                scheduler = new LongPollScheduler(session, asyncContext, reply, browserId);
                                                request.setAttribute(LongPollScheduler.ATTRIBUTE, scheduler);
                                                session.setScheduler(scheduler);
                                                reply = null;
                                                metaConnectSuspended(asyncContext, session);
                                            }
                                            else
                                            {
                                                decBrowserId(browserId);
                                            }
                                        }
                                        else
                                        {
                                            // There are multiple sessions from the same browser
                                            Map<String, Object> advice = reply.getAdvice(true);

                                            if (browserId != null)
                                                advice.put("multiple-clients", true);

                                            long multiSessionInterval = getMultiSessionInterval();
                                            if (multiSessionInterval > 0)
                                            {
                                                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
                                                advice.put(Message.INTERVAL_FIELD, multiSessionInterval);
                                            }
                                            else
                                            {
                                                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                                                reply.setSuccessful(false);
                                            }
                                            session.reAdvise();
                                        }
                                    }
                                }
                                finally
                                {
                                    if (reply != null)
                                        writer = writeQueueForMetaConnect(request, response, session, writer);
                                }
                            }
                            else
                            {
                                if (!isMetaConnectDeliveryOnly() && !session.isMetaConnectDeliveryOnly())
                                {
                                    writer = writeQueue(request, response, session, writer);
                                }
                            }
                        }

                        // If the reply has not been otherwise handled, send it
                        if (reply != null)
                        {
                            if (connect && session != null && session.isDisconnected())
                                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);

                            reply = getBayeux().extendReply(session, session, reply);

                            if (reply != null)
                            {
                                getBayeux().freeze(reply);
                                writer = writeMessage(request, response, writer, session, reply);
                            }
                        }
                    }

                    // Disassociate the reply
                    message.setAssociated(null);
                }
                if (writer != null)
                    finishWrite(writer, session);
            }
            catch (ParseException x)
            {
                handleJSONParseException(request, response, x.getMessage(), x.getCause());
            }
            finally
            {
                // If we started a batch, end it now
                if (batch)
                {
                    // Flush session if not done by the batch, since some browser order <script> requests
                    if (!session.endBatch() && isAlwaysFlushingAfterHandle())
                        session.flush();
                }
                else if (session != null && !connect && isAlwaysFlushingAfterHandle())
                {
                    session.flush();
                }
            }
        }
        else
        {
            // Get the resumed session
            ServerSessionImpl session = scheduler.getSession();
            metaConnectResumed(request.getAsyncContext(), session);

            PrintWriter writer = writeQueueForMetaConnect(request, response, session, null);

            // Send the connect reply
            ServerMessage.Mutable reply = scheduler.getReply();

            if (session.isDisconnected())
                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);

            reply = getBayeux().extendReply(session, session, reply);

            if (reply != null)
            {
                getBayeux().freeze(reply);
                writer = writeMessage(request, response, writer, session, reply);
            }

            finishWrite(writer, session);
        }
    }

    private PrintWriter writeQueueForMetaConnect(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, PrintWriter writer) throws IOException
    {
        try
        {
            return writeQueue(request, response, session, writer);
        }
        finally
        {
            // We need to start the interval timeout after we sent the queue
            // (which may take time) but before sending the connect reply
            // otherwise we open up a race condition where the client receives
            // the connect reply and sends a new connect request before we start
            // the interval timeout, which will be wrong.
            // We need to put this into a finally block in case sending the queue
            // throws an exception (for example because the client is gone), so that
            // we start the interval timeout that is important to sweep the session
            if (session.isConnected())
                session.startIntervalTimeout(getInterval());
        }
    }

    private PrintWriter writeQueue(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, PrintWriter writer)
            throws IOException
    {
        List<ServerMessage> queue = session.takeQueue();
        for (ServerMessage m : queue)
            writer = writeMessage(request, response, writer, session, m);
        return writer;
    }

    protected ServerMessage.Mutable[] parseMessages(String[] requestParameters) throws IOException, ParseException
    {
        if (requestParameters == null || requestParameters.length == 0)
            throw new IOException("Missing '" + MESSAGE_PARAM + "' request parameter");

        if (requestParameters.length == 1)
            return parseMessages(requestParameters[0]);

        List<ServerMessage.Mutable> messages = new ArrayList<>();
        for (String batch : requestParameters)
        {
            if (batch == null)
                continue;
            messages.addAll(Arrays.asList(parseMessages(batch)));
        }
        return messages.toArray(new ServerMessage.Mutable[messages.size()]);
    }

    protected abstract ServerMessage.Mutable[] parseMessages(HttpServletRequest request) throws IOException, ParseException;

    /**
     * @return true if the transport always flushes at the end of a call to {@link #handle(HttpServletRequest, HttpServletResponse)}.
     */
    protected abstract boolean isAlwaysFlushingAfterHandle();

    protected abstract PrintWriter writeMessage(HttpServletRequest request, HttpServletResponse response, PrintWriter writer, ServerSessionImpl session, ServerMessage message) throws IOException;

    protected abstract void finishWrite(PrintWriter writer, ServerSessionImpl session) throws IOException;

    private class LongPollScheduler implements AbstractServerTransport.OneTimeScheduler, AsyncListener
    {
        private static final String ATTRIBUTE = "org.cometd.scheduler";

        private final ServerSessionImpl _session;
        private final AsyncContext _asyncContext;
        private final ServerMessage.Mutable _reply;
        private volatile String _browserId;
        private volatile boolean _expired;

        public LongPollScheduler(ServerSessionImpl session, AsyncContext asyncContext, ServerMessage.Mutable reply, String browserId)
        {
            _session = session;
            _asyncContext = asyncContext;
            _asyncContext.addListener(this);
            _reply = reply;
            _browserId = browserId;
        }

        public void cancel()
        {
            if (_asyncContext.getRequest().isAsyncStarted() && !_expired)
            {
                try
                {
                    _logger.debug("Duplicate /meta/connect, canceling {}", _reply);
                    decBrowserId();
                    ServletResponse response = _asyncContext.getResponse();
                    ((HttpServletResponse)response).sendError(HttpServletResponse.SC_REQUEST_TIMEOUT);
                }
                catch (IOException x)
                {
                    _logger.trace("", x);
                }

                try
                {
                    _asyncContext.complete();
                }
                catch (Exception x)
                {
                    _logger.trace("", x);
                }
            }
        }

        public void schedule()
        {
            decBrowserId();
            dispatch();
        }

        public ServerSessionImpl getSession()
        {
            return _session;
        }

        public ServerMessage.Mutable getReply()
        {
            Map<String, Object> advice = _session.takeAdvice(LongPollingTransport.this);
            if (advice != null)
                _reply.put(Message.ADVICE_FIELD, advice);
            return _reply;
        }

        @Override
        public void onStartAsync(AsyncEvent asyncEvent) throws IOException
        {
            _expired = false;
        }

        @Override
        public void onComplete(AsyncEvent asyncEvent) throws IOException
        {
            decBrowserId();
        }

        @Override
        public void onTimeout(AsyncEvent asyncEvent) throws IOException
        {
            _expired = true;
            _session.setScheduler(null);
            dispatch();
        }

        private void dispatch()
        {
            // We dispatch() when either we are suspended or timed out, instead of doing a write() + complete().
            // If we have to write a message to 10 clients, and the first client write() blocks, then we would
            // be delaying the other 9 clients.
            // By always calling dispatch() we allow each write to be on its own thread, and it may block without
            // affecting other writes.
            // Only with Servlet 3.1 and standard asynchronous I/O we would be able to do write() + complete()
            // without blocking, and it will be much more efficient because there is no thread dispatching and
            // there will be more mechanical sympathy.
            _asyncContext.dispatch();
        }

        @Override
        public void onError(AsyncEvent asyncEvent) throws IOException
        {
        }

        private void decBrowserId()
        {
            LongPollingTransport.this.decBrowserId(_browserId);
            _browserId = null;
        }
    }
}
