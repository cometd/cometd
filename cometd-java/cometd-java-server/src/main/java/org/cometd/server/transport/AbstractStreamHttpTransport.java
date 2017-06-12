/*
 * Copyright (c) 2008-2017 the original author or authors.
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;

/**
 * <p>The base class for HTTP transports that use blocking stream I/O.</p>
 */
public abstract class AbstractStreamHttpTransport extends AbstractHttpTransport {
    private static final String CONTEXT_ATTRIBUTE = "org.cometd.transport.context";
    private static final String HEARTBEAT_TIMEOUT_ATTRIBUTE = "org.cometd.transport.heartbeat.timeout";

    protected AbstractStreamHttpTransport(BayeuxServerImpl bayeux, String name) {
        super(bayeux, name);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        // API calls could be async, so we must be async in the request processing too.
        AsyncContext asyncContext = request.startAsync();
        // Explicitly disable the timeout, to prevent
        // that the timeout fires in case of slow reads.
        asyncContext.setTimeout(0);

        Promise<Void> promise = new Promise<Void>() {
            @Override
            public void succeed(Void result) {
                reset();
                asyncContext.complete();
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Handling successful");
                }
            }

            @Override
            public void fail(Throwable failure) {
                reset();
                int code = failure instanceof TimeoutException ?
                        HttpServletResponse.SC_REQUEST_TIMEOUT :
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                sendError(request, response, code, failure);
                asyncContext.complete();
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Handling failed", failure);
                }
            }

            private void reset() {
                setCurrentRequest(null);
                getBayeux().setCurrentTransport(null);
            }
        };

        Context context = (Context)request.getAttribute(CONTEXT_ATTRIBUTE);
        if (context == null) {
            getBayeux().setCurrentTransport(this);
            setCurrentRequest(request);
            process(new Context(request, response), promise);
        } else {
            ServerMessage.Mutable message = context.scheduler.getMessage();
            context.session.notifyResumed(message, (Boolean)request.getAttribute(HEARTBEAT_TIMEOUT_ATTRIBUTE));
            resume(context, message, Promise.from(y -> flush(context, promise), promise::fail));
        }
    }

    protected void process(Context context, Promise<Void> promise) {
        HttpServletRequest request = context.request;
        try {
            try {
                ServerMessage.Mutable[] messages = parseMessages(request);
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Parsed {} messages", messages == null ? -1 : messages.length);
                }
                if (messages != null) {
                    processMessages(context, messages, promise);
                } else {
                    promise.succeed(null);
                }
            } catch (ParseException x) {
                handleJSONParseException(request, context.response, x.getMessage(), x.getCause());
                promise.succeed(null);
            }
        } catch (Throwable x) {
            promise.fail(x);
        }
    }

    @Override
    protected HttpScheduler suspend(Context context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
        HttpServletRequest request = context.request;
        context.scheduler = newHttpScheduler(context, promise, message, timeout);
        request.setAttribute(CONTEXT_ATTRIBUTE, context);
        if (_logger.isDebugEnabled()) {
            _logger.debug("Suspended {}", message);
        }
        context.session.notifySuspended(message, timeout);
        return context.scheduler;
    }

    protected HttpScheduler newHttpScheduler(Context context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
        return new DispatchingLongPollScheduler(context, promise, message, timeout);
    }

    protected abstract ServerMessage.Mutable[] parseMessages(HttpServletRequest request) throws IOException, ParseException;

    protected ServerMessage.Mutable[] parseMessages(String[] requestParameters) throws IOException, ParseException {
        if (requestParameters == null || requestParameters.length == 0) {
            throw new IOException("Missing '" + MESSAGE_PARAM + "' request parameter");
        }

        if (requestParameters.length == 1) {
            return parseMessages(requestParameters[0]);
        }

        List<ServerMessage.Mutable> messages = new ArrayList<>();
        for (String batch : requestParameters) {
            if (batch == null) {
                continue;
            }
            ServerMessage.Mutable[] parsed = parseMessages(batch);
            if (parsed != null) {
                messages.addAll(Arrays.asList(parsed));
            }
        }
        return messages.toArray(new ServerMessage.Mutable[messages.size()]);
    }

    @Override
    protected void write(Context context, List<ServerMessage> messages, Promise<Void> promise) {
        HttpServletRequest request = context.request;
        HttpServletResponse response = context.response;
        try {
            ServerSessionImpl session = context.session;
            List<ServerMessage.Mutable> replies = context.replies;
            int replyIndex = 0;
            boolean needsComma = false;
            ServletOutputStream output;
            try {
                output = beginWrite(request, response);

                // First message is always the handshake reply, if any.
                if (replies.size() > 0) {
                    ServerMessage.Mutable reply = replies.get(0);
                    if (Channel.META_HANDSHAKE.equals(reply.getChannel())) {
                        if (allowMessageDeliveryDuringHandshake(session) && !messages.isEmpty()) {
                            reply.put("x-messages", messages.size());
                        }
                        getBayeux().freeze(reply);
                        writeMessage(response, output, session, reply);
                        needsComma = true;
                        ++replyIndex;
                    }
                }

                // Write the messages.
                for (ServerMessage message : messages) {
                    if (needsComma) {
                        output.write(',');
                    }
                    needsComma = true;
                    writeMessage(response, output, session, message);
                }
            } finally {
                // Start the interval timeout after writing the messages
                // since they may take time to be written, even in case
                // of exceptions to make sure the session can be swept.
                if (context.scheduleExpiration && session != null && (session.isHandshook() || session.isConnected())) {
                    session.scheduleExpiration(getInterval());
                }
            }

            // Write the replies, if any.
            while (replyIndex < replies.size()) {
                ServerMessage.Mutable reply = replies.get(replyIndex);
                if (reply != null) {
                    if (needsComma) {
                        output.write(',');
                    }
                    needsComma = true;
                    getBayeux().freeze(reply);
                    writeMessage(response, output, session, reply);
                }
                ++replyIndex;
            }

            endWrite(response, output);

            promise.succeed(null);
        } catch (Throwable x) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Failure writing messages", x);
            }
            promise.fail(x);
        }
    }

    protected void writeMessage(HttpServletResponse response, ServletOutputStream output, ServerSessionImpl session, ServerMessage message) throws IOException {
        output.write(toJSONBytes(message, response.getCharacterEncoding()));
    }

    protected abstract ServletOutputStream beginWrite(HttpServletRequest request, HttpServletResponse response) throws IOException;

    protected abstract void endWrite(HttpServletResponse response, ServletOutputStream output) throws IOException;

    protected class DispatchingLongPollScheduler extends LongPollScheduler {
        public DispatchingLongPollScheduler(Context context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
            super(context, promise, message, timeout);
        }

        @Override
        protected void dispatch(boolean timeout) {
            // We dispatch() when either we are suspended or timed out, instead of doing a write() + complete().
            // If we have to write a message to 10 clients, and the first client write() blocks, then we would
            // be delaying the other 9 clients.
            // By always calling dispatch() we allow each write to be on its own thread, and it may block without
            // affecting other writes.
            // Only with Servlet 3.1 and standard asynchronous I/O we would be able to do write() + complete()
            // without blocking, and it will be much more efficient because there is no thread dispatching and
            // there will be more mechanical sympathy.
            HttpServletRequest request = getContext().request;
            request.setAttribute(HEARTBEAT_TIMEOUT_ATTRIBUTE, timeout);
            request.getAsyncContext().dispatch();
        }
    }
}
