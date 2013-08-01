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
    private static final String SCHEDULER_ATTRIBUTE = "org.cometd.scheduler";

    protected AsyncLongPollingTransport(BayeuxServerImpl bayeux, String name)
    {
        super(bayeux, name);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        AsyncContext asyncContext = request.startAsync();
        ServletInputStream input = request.getInputStream();
        Context context = new Context(asyncContext);
        input.setReadListener(context);
        context.read(input);
    }

    protected void processMessages(Context context, ServerMessage.Mutable[] messages)
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
                        ServerMessage.Mutable reply = messages[i] = processMetaHandshake(context, session, message);
                        if (reply != null)
                            session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());
                        break;
                    }
                    case Channel.META_CONNECT:
                    {
                        ServerMessage.Mutable reply = messages[i] = processMetaConnect(context, session, message);
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
                flush(context, session, metaConnect, messages);
        }
        finally
        {
            if (batch)
                session.endBatch();
        }
    }

    protected ServerMessage.Mutable processMetaHandshake(Context context, ServerSessionImpl session, ServerMessage.Mutable message)
    {
        ServerMessage.Mutable reply = bayeuxServerHandle(session, message);
        if (reply != null)
        {
            session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());
            if (session != null)
            {
                HttpServletRequest request = (HttpServletRequest)context.asyncContext.getRequest();
                String userAgent = request.getHeader("User-Agent");
                session.setUserAgent(userAgent);

                String browserId = findBrowserId(request);
                if (browserId == null)
                    setBrowserId(request, (HttpServletResponse)context.asyncContext.getResponse());
            }
        }
        return processReply(session, reply);
    }

    protected ServerMessage.Mutable processMetaConnect(Context context, ServerSessionImpl session, ServerMessage.Mutable message)
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
                HttpServletRequest request = (HttpServletRequest)context.asyncContext.getRequest();
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

                        // Set the timeout and wait for messages
                        AsyncContext asyncContext = request.getAsyncContext();
                        if (asyncContext == null)
                            asyncContext = request.startAsync();
                        asyncContext.setTimeout(timeout);

                        // This scheduler is slightly different from the other in that
                        // we don't dispatch timeouts but always write asynchronously
                        Scheduler scheduler = new LongPollingScheduler(context, session, reply, browserId);
                        request.setAttribute(SCHEDULER_ATTRIBUTE, scheduler);

                        // TODO: perhaps best here to return bool to indicate the scheduler was set.
                        // This would avoid a race condition between this thread trying to write the queue
                        // and the scheduler resuming trying to write the queue... but perhaps scheduler.schedule()
                        // won't dispatch so there is no race.
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

    protected void flush(Context context, ServerSessionImpl session, boolean startInterval, ServerMessage.Mutable... replies)
    {
        context.write(session, startInterval, replies);
    }

    protected class Context implements ReadListener, WriteListener
    {
        private static final int CAPACITY = 512;

        private final Utf8StringBuilder content = new Utf8StringBuilder(CAPACITY);
        private final byte[] buffer = new byte[CAPACITY];
        private final AsyncContext asyncContext;
        private boolean writeComplete;

        protected Context(AsyncContext asyncContext)
        {
            this.asyncContext = asyncContext;
        }

        protected void read(ServletInputStream input) throws IOException
        {
            _logger.debug("Asynchronous read start from {}", input);
            while (input.isReady())
            {
                int read = input.read(buffer);
                _logger.debug("Asynchronous read {} bytes from {}", read, input);
                content.append(buffer, 0, read);
            }
            if (input.isFinished())
            {
                String json = content.toString();
                content.reset();
                _logger.debug("Asynchronous read end from {}: {}", input, json);
                process(json);
            }
            else
            {
                _logger.debug("Asynchronous read pending from {}", input);
            }
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            read(asyncContext.getRequest().getInputStream());
        }

        @Override
        public void onAllDataRead() throws IOException
        {
        }

        protected void process(String json) throws IOException
        {
            try
            {
                ServerMessage.Mutable[] messages = parseMessages(json);
                _logger.debug("Parsed {} messages", messages.length);
                processMessages(this, messages);
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
            _logger.debug("", throwable);
            asyncContext.complete();
        }

        protected void write(ServerSessionImpl session, boolean startInterval, ServerMessage.Mutable[] replies)
        {
            // TODO: handle isMetaConnectDeliveryOnly

            List<ServerMessage> messages = Collections.emptyList();
            if (session != null)
                messages = session.takeQueue();
            _logger.debug("Messages to write for session {}: {}", session, messages.size());

            StringBuilder builder1 = new StringBuilder((messages.size() + replies.length) * 4 * 32);
            for (int i = 0; i < messages.size(); ++i)
            {
                ServerMessage message = messages.get(i);
                builder1.append(i == 0 ? "[" : ",");
                builder1.append(message.getJSON());
            }
            _logger.debug("Messages to write for session {}: {}", session, builder1.toString());

            StringBuilder builder2 = null;
            if (startInterval)
                builder2 = new StringBuilder(replies.length * 4 * 32);

            StringBuilder builder = startInterval ? builder2 : builder1;
            for (ServerMessage.Mutable reply : replies)
            {
                if (reply != null)
                {
                    builder.append(builder.length() == 0 ? "[" : ",");
                    builder.append(reply.getJSON());
                }
            }
            if (builder.length() > 0)
                builder.append("]");
            _logger.debug("Replies to write for session {}: {}", session, builder);

            assert builder.length() > 0;

            try
            {
                // Always write asynchronously
                ServletResponse response = asyncContext.getResponse();
                response.setContentType("application/json;charset=UTF-8");
                ServletOutputStream output = response.getOutputStream();
                output.setWriteListener(startInterval ? new RepliesWriter(session, builder2.toString()) : this);
                if (writeComplete = write(output, builder1.toString()) && !startInterval)
                    asyncContext.complete();
            }
            catch (IOException x)
            {
                onError(x);
            }
        }

        private boolean write(ServletOutputStream output, String data) throws IOException
        {
            _logger.debug("Asynchronous write start on {}: {}", output, data);
            if (output.isReady())
            {
                byte[] bytes = data.getBytes("UTF-8");
                output.write(bytes);
            }
            boolean result = output.isReady();
            _logger.debug("Asynchronous write end on {}: {}", output, result ? "complete" : "pending");
            return result;
        }

        @Override
        public void onWritePossible() throws IOException
        {
            if (!writeComplete)
                asyncContext.complete();
        }

        private class RepliesWriter implements WriteListener
        {
            private final ServerSessionImpl session;
            private final String content;
            private Boolean written;

            private RepliesWriter(ServerSessionImpl session, String content)
            {
                this.session = session;
                this.content = content;
            }

            @Override
            public void onWritePossible() throws IOException
            {
                if (written == null)
                {
                    written = Boolean.FALSE;
                    if (session != null && session.isConnected())
                        session.startIntervalTimeout(getInterval());
                    if (written = write(asyncContext.getResponse().getOutputStream(), content))
                        asyncContext.complete();
                }
                else if (written == Boolean.FALSE)
                {
                    asyncContext.complete();
                }
            }

            @Override
            public void onError(Throwable throwable)
            {
                Context.this.onError(throwable);
            }
        }
    }

    private class LongPollingScheduler implements OneTimeScheduler, AsyncListener
    {
        private final Context context;
        private final ServerSessionImpl session;
        private final ServerMessage.Mutable reply;
        private final String browserId;
        private boolean expired;

        private LongPollingScheduler(Context context, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId)
        {
            this.context = context;
            this.session = session;
            this.reply = reply;
            this.browserId = browserId;
            context.asyncContext.addListener(this);
        }

        @Override
        public void schedule()
        {
            decBrowserId(browserId);
            _logger.debug("Resuming /meta/connect after schedule");
            resume();
        }

        @Override
        public void cancel()
        {
            if (expired || context.asyncContext.getRequest().getAttribute(SCHEDULER_ATTRIBUTE) == null)
                return;

            _logger.debug("Duplicate /meta/connect, cancelling {}", reply);

            int responseCode = HttpServletResponse.SC_REQUEST_TIMEOUT;
            try
            {
                HttpServletResponse response = (HttpServletResponse)context.asyncContext.getResponse();
                if (!response.isCommitted())
                    response.sendError(responseCode);
            }
            catch (IOException x)
            {
                _logger.trace("Could not send " + responseCode + " response", x);
            }

            try
            {
                context.asyncContext.complete();
            }
            catch (Exception x)
            {
                _logger.trace("Could not complete " + responseCode + " response", x);
            }
        }

        @Override
        public void onStartAsync(AsyncEvent asyncEvent) throws IOException
        {
        }

        @Override
        public void onTimeout(AsyncEvent asyncEvent) throws IOException
        {
            expired = true;
            session.setScheduler(null);
            _logger.debug("Resuming /meta/connect after timeout");
            resume();
        }

        private void resume()
        {
            metaConnectResumed(context.asyncContext, session);
            if (session.isDisconnected())
                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
            flush(context, session, true, processReply(session, reply));
        }

        @Override
        public void onComplete(AsyncEvent asyncEvent) throws IOException
        {
            decBrowserId(browserId);
        }

        @Override
        public void onError(AsyncEvent asyncEvent) throws IOException
        {
        }
    }
}
