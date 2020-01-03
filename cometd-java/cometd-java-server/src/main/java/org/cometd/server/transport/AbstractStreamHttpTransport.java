/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;

/**
 * <p>The base class for HTTP transports that use blocking stream I/O.</p>
 */
public abstract class AbstractStreamHttpTransport extends AbstractHttpTransport {
    private static final String SCHEDULER_ATTRIBUTE = "org.cometd.scheduler";

    protected AbstractStreamHttpTransport(BayeuxServerImpl bayeux, String name) {
        super(bayeux, name);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        getBayeux().setCurrentTransport(this);
        setCurrentRequest(request);
        try {
            process(request, response);
        } finally {
            setCurrentRequest(null);
            getBayeux().setCurrentTransport(null);
        }
    }

    protected void process(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        LongPollScheduler scheduler = (LongPollScheduler)request.getAttribute(SCHEDULER_ATTRIBUTE);
        if (scheduler == null) {
            // Not a resumed /meta/connect, process messages.
            try {
                ServerMessage.Mutable[] messages = parseMessages(request);
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Parsed {} messages", messages == null ? -1 : messages.length);
                }
                if (messages != null) {
                    processMessages(request, response, messages);
                }
            } catch (ParseException x) {
                handleJSONParseException(request, response, x.getMessage(), x.getCause());
            }
        } else {
            resume(request, response, null, scheduler.getServerSession(), scheduler.getMetaConnectReply());
        }
    }

    @Override
    protected HttpScheduler suspend(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable reply, long timeout) {
        AsyncContext asyncContext = request.startAsync(request, response);
        asyncContext.setTimeout(0);
        HttpScheduler scheduler = newHttpScheduler(request, response, asyncContext, session, reply, timeout);
        request.setAttribute(SCHEDULER_ATTRIBUTE, scheduler);
        return scheduler;
    }

    protected HttpScheduler newHttpScheduler(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, long timeout) {
        return new DispatchingLongPollScheduler(request, response, asyncContext, session, reply, timeout);
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
    @SuppressWarnings("ForLoopReplaceableByForEach")
    protected void write(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, boolean scheduleExpiration, List<ServerMessage> messages, ServerMessage.Mutable[] replies) {
        try {
            int replyIndex = 0;
            boolean needsComma = false;
            ServletOutputStream output;
            try {
                output = beginWrite(request, response);

                // First message is always the handshake reply, if any.
                if (replies.length > 0) {
                    ServerMessage.Mutable reply = replies[0];
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
                for (int i = 0; i < messages.size(); ++i) {
                    ServerMessage message = messages.get(i);
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
                if (scheduleExpiration) {
                    scheduleExpiration(session);
                }
            }

            // Write the replies, if any.
            while (replyIndex < replies.length) {
                ServerMessage.Mutable reply = replies[replyIndex];
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
            writeComplete(request, response, session, messages, replies);
        } catch (Throwable x) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Failure writing messages", x);
            }
            error(request, response, getAsyncContext(request), HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected void writeMessage(HttpServletResponse response, ServletOutputStream output, ServerSessionImpl session, ServerMessage message) throws IOException {
        output.write(toJSONBytes(message, response.getCharacterEncoding()));
    }

    protected abstract ServletOutputStream beginWrite(HttpServletRequest request, HttpServletResponse response) throws IOException;

    protected abstract void endWrite(HttpServletResponse response, ServletOutputStream output) throws IOException;

    protected void writeComplete(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, List<ServerMessage> messages, ServerMessage.Mutable[] replies) {
    }

    protected class DispatchingLongPollScheduler extends LongPollScheduler {
        public DispatchingLongPollScheduler(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, long timeout) {
            super(request, response, asyncContext, session, reply, timeout);
        }

        @Override
        protected void dispatch() {
            // We dispatch() when either we are suspended or timed out, instead of doing a write() + complete().
            // If we have to write a message to 10 clients, and the first client write() blocks, then we would
            // be delaying the other 9 clients.
            // By always calling dispatch() we allow each write to be on its own thread, and it may block without
            // affecting other writes.
            // Only with Servlet 3.1 and standard asynchronous I/O we would be able to do write() + complete()
            // without blocking, and it will be much more efficient because there is no thread dispatching and
            // there will be more mechanical sympathy.
            getAsyncContext().dispatch();
        }
    }
}
