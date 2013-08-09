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
import java.text.ParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.util.Utf8StringBuilder;

public abstract class AsyncLongPollingTransport extends HttpTransport
{
    protected AsyncLongPollingTransport(BayeuxServerImpl bayeux, String name)
    {
        super(bayeux, name);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        AsyncContext asyncContext = request.startAsync();
        ServletInputStream input = request.getInputStream();
        Reader reader = new Reader(asyncContext);
        input.setReadListener(reader);
    }

    protected void processMessages(AsyncContext asyncContext, ServerMessage.Mutable[] messages) throws IOException
    {
        boolean autoBatch = isAutoBatch();
        ServerSessionImpl session = null;
        boolean batch = false;
        boolean metaConnect = false;
        boolean suspended = false;
        try
        {
            for (int i = 0; i < messages.length; ++i)
            {
                ServerMessage.Mutable message = messages[i];
                _logger.debug("Processing message {}", message);

                if (session == null)
                    session = (ServerSessionImpl)getBayeux().getSession(message.getClientId());

                if (session != null && autoBatch && !batch)
                {
                    batch = true;
                    session.startBatch();
                }

                switch (message.getChannel())
                {
                    case Channel.META_HANDSHAKE:
                    {
                        ServerMessage.Mutable reply = messages[i] = processMetaHandshake(asyncContext, session, message);
                        if (reply != null)
                            session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());
                        break;
                    }
                    case Channel.META_CONNECT:
                    {
                        ServerMessage.Mutable reply = messages[i] = processMetaConnect(asyncContext, session, message);
                        metaConnect = true;
                        if (reply == null)
                            suspended = messages.length == 1;
                        break;
                    }
                    default:
                    {
                        ServerMessage.Mutable reply = bayeuxServerHandle(session, message);
                        messages[i] = processReply(session, reply);
                        break;
                    }
                }
            }

            if (!suspended)
                flush(asyncContext, session, metaConnect, messages);
        }
        finally
        {
            if (batch)
                session.endBatch();
        }
    }

    protected ServerMessage.Mutable processMetaHandshake(AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable message)
    {
        ServerMessage.Mutable reply = bayeuxServerHandle(session, message);
        if (reply != null)
        {
            session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());
            if (session != null)
            {
                HttpServletRequest request = (HttpServletRequest)asyncContext.getRequest();
                String userAgent = request.getHeader("User-Agent");
                session.setUserAgent(userAgent);

                String browserId = findBrowserId(request);
                if (browserId == null)
                    setBrowserId(request, (HttpServletResponse)asyncContext.getResponse());
            }
        }
        return processReply(session, reply);
    }

    protected ServerMessage.Mutable processMetaConnect(AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable message)
    {
        if (session != null)
        {
            // Cancel the previous scheduler to cancel any prior waiting long poll.
            // This should also decrement the browser ID.
            session.setScheduler(null);
        }

        boolean wasConnected = session != null && session.isConnected();
        ServerMessage.Mutable reply = bayeuxServerHandle(session, message);
        if (reply != null && session != null)
        {
            if (!session.hasNonLazyMessages() && reply.isSuccessful())
            {
                // Detect if we have multiple sessions from the same browser
                // Note that CORS requests do not send cookies, so we need to handle them specially
                // CORS requests always have the Origin header
                HttpServletRequest request = (HttpServletRequest)asyncContext.getRequest();
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


                        LongPollingScheduler scheduler = new LongPollingScheduler(asyncContext, session, reply, browserId);
                        scheduler.scheduleTimeout(timeout);

                        metaConnectSuspended(asyncContext, session);
                        // Setting the scheduler may resume the /meta/connect
                        session.setScheduler(scheduler);
                        reply = null;
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

            if (reply != null && session.isDisconnected())
                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
        }

        return processReply(session, reply);
    }

    protected ServerMessage.Mutable processReply(ServerSessionImpl session, ServerMessage.Mutable reply)
    {
        if (reply != null)
        {
            reply = getBayeux().extendReply(session, session, reply);
            if (reply != null)
                getBayeux().freeze(reply);
        }
        return reply;
    }

    protected void flush(AsyncContext asyncContext, ServerSessionImpl session, boolean startInterval, ServerMessage.Mutable... replies)
    {
        try
        {
            List<ServerMessage> messages = Collections.emptyList();
            if (session != null)
            {
                if (startInterval || !isMetaConnectDeliveryOnly() && !session.isMetaConnectDeliveryOnly())
                    messages = session.takeQueue();
            }

            // Always write asynchronously
            ServletResponse response = asyncContext.getResponse();
            response.setContentType("application/json;charset=UTF-8");
            ServletOutputStream output = response.getOutputStream();
            output.setWriteListener(new Writer(asyncContext, session, startInterval, messages, replies));
        }
        catch (IOException x)
        {
            error(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void error(AsyncContext asyncContext, int responseCode)
    {
        try
        {
            HttpServletResponse response = (HttpServletResponse)asyncContext.getResponse();
            if (!response.isCommitted())
                response.sendError(responseCode);
        }
        catch (IOException x)
        {
            _logger.trace("Could not send " + responseCode + " response", x);
        }

        try
        {
            asyncContext.complete();
        }
        catch (Exception x)
        {
            _logger.trace("Could not complete " + responseCode + " response", x);
        }
    }

    protected class Reader implements ReadListener
    {
        private static final int CAPACITY = 512;

        private final Utf8StringBuilder content = new Utf8StringBuilder(CAPACITY);
        private final byte[] buffer = new byte[CAPACITY];
        private final AsyncContext asyncContext;

        protected Reader(AsyncContext asyncContext)
        {
            this.asyncContext = asyncContext;
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            ServletInputStream input = asyncContext.getRequest().getInputStream();
            _logger.debug("Asynchronous read start from {}", input);
            while (input.isReady())
            {
                int read = input.read(buffer);
                _logger.debug("Asynchronous read {} bytes from {}", read, input);
                content.append(buffer, 0, read);
            }
            if (!input.isFinished())
                _logger.debug("Asynchronous read pending from {}", input);
        }

        @Override
        public void onAllDataRead() throws IOException
        {
            ServletInputStream input = asyncContext.getRequest().getInputStream();
            String json = content.toString();
            content.reset();
            _logger.debug("Asynchronous read end from {}: {}", input, json);
            process(json);
        }

        protected void process(String json) throws IOException
        {
            try
            {
                ServerMessage.Mutable[] messages = parseMessages(json);
                _logger.debug("Parsed {} messages", messages.length);
                processMessages(asyncContext, messages);
            }
            catch (ParseException x)
            {
                handleJSONParseException((HttpServletRequest)asyncContext.getRequest(),
                        (HttpServletResponse)asyncContext.getResponse(), json, x);
                asyncContext.complete();
            }
        }

        @Override
        public void onError(Throwable throwable)
        {
            error(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected class Writer implements WriteListener
    {
        private final StringBuilder buffer = new StringBuilder(512);
        private final AsyncContext asyncContext;
        private final ServerSessionImpl session;
        private final boolean startInterval;
        private final List<ServerMessage> messages;
        private final ServerMessage.Mutable[] replies;
        private int messageIndex = -1;
        private int replyIndex;

        public Writer(AsyncContext asyncContext, ServerSessionImpl session, boolean startInterval, List<ServerMessage> messages, ServerMessage.Mutable[] replies)
        {
            this.asyncContext = asyncContext;
            this.session = session;
            this.startInterval = startInterval;
            this.messages = messages;
            this.replies = replies;
        }

        @Override
        public void onWritePossible() throws IOException
        {
            ServletOutputStream output = asyncContext.getResponse().getOutputStream();
            if (messageIndex < 0)
            {
                messageIndex = 0;
                buffer.append("[");
            }

            _logger.debug("Messages to write for session {}: {}", session, messages.size());
            while (messageIndex < messages.size())
            {
                if (messageIndex > 0)
                    buffer.append(",");

                buffer.append(messages.get(messageIndex++).getJSON());
                output.write(buffer.toString().getBytes("UTF-8"));
                buffer.setLength(0);
                if (!output.isReady())
                    return;
            }

            if (replyIndex == 0 && startInterval && session != null && session.isConnected())
                session.startIntervalTimeout(getInterval());

            _logger.debug("Replies to write for session {}: {}", session, replies.length);
            while (replyIndex < replies.length)
            {
                ServerMessage.Mutable reply = replies[replyIndex++];
                if (reply == null)
                    continue;

                buffer.append(reply.getJSON());
                if (replyIndex == replies.length)
                    buffer.append("]");

                output.write(buffer.toString().getBytes("UTF-8"));
                buffer.setLength(0);
                if (!output.isReady())
                    return;
            }

            asyncContext.complete();
        }

        @Override
        public void onError(Throwable throwable)
        {
            error(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private class LongPollingScheduler implements Runnable, OneTimeScheduler, AsyncListener
    {
        private final AsyncContext asyncContext;
        private final ServerSessionImpl session;
        private final ServerMessage.Mutable reply;
        private final String browserId;
        private volatile org.eclipse.jetty.util.thread.Scheduler.Task task;

        private LongPollingScheduler(AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId)
        {
            this.asyncContext = asyncContext;
            this.session = session;
            this.reply = reply;
            this.browserId = browserId;
            asyncContext.addListener(this);
        }

        @Override
        public void schedule()
        {
            if (cancelTimeout())
            {
                _logger.debug("Resuming /meta/connect after schedule");
                resume();
            }
        }

        @Override
        public void cancel()
        {
            if (cancelTimeout())
            {
                _logger.debug("Duplicate /meta/connect, cancelling {}", reply);
                error(asyncContext, HttpServletResponse.SC_REQUEST_TIMEOUT);
            }
        }

        private void scheduleTimeout(long timeout)
        {
            task = getBayeux().schedule(this, timeout);
        }

        private boolean cancelTimeout()
        {
            org.eclipse.jetty.util.thread.Scheduler.Task task = this.task;
            return task != null && task.cancel();
        }

        @Override
        public void run()
        {
            task = null;
            session.setScheduler(null);
            _logger.debug("Resuming /meta/connect after timeout");
            resume();
        }

        private void resume()
        {
            metaConnectResumed(asyncContext, session);
            if (session.isDisconnected())
                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
            flush(asyncContext, session, true, processReply(session, reply));
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onComplete(AsyncEvent asyncEvent) throws IOException
        {
            decBrowserId(browserId);
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            error(asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
