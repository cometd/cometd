/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.server.servlet.transport;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import org.cometd.api.CometDOutput;
import org.cometd.api.CometDRequest;
import org.cometd.api.CometDResponse;
import org.cometd.api.HttpException;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.http.AbstractHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The base class for HTTP transports that use blocking stream I/O.</p>
 */
public abstract class AbstractStreamHttpTransport extends AbstractHttpTransport
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractStreamHttpTransport.class);
    private static final String CONTEXT_ATTRIBUTE = "org.cometd.transport.context";
    private static final String HEARTBEAT_TIMEOUT_ATTRIBUTE = "org.cometd.transport.heartbeat.timeout";

    protected AbstractStreamHttpTransport(BayeuxServerImpl bayeux, String name) {
        super(bayeux, name);
    }

    @Override
    public void handle(BayeuxContext bayeuxContext, CometDRequest request, CometDResponse response, Promise<Void> p) {
        Promise<Void> promise = new Promise<>() {
            @Override
            public void succeed(Void result)
            {
                p.succeed(result);
            }

            @Override
            public void fail(Throwable failure)
            {
                int code = failure instanceof TimeoutException ?
                    getDuplicateMetaConnectHttpResponseCode() :
                    CometDResponse.SC_INTERNAL_SERVER_ERROR;
                p.fail(new HttpException(code, failure));
            }
        };

        Context context = (Context)request.getAttribute(CONTEXT_ATTRIBUTE);
        if (context == null) {
            process(new ContextInServlet(request, response, bayeuxContext), promise);
        } else {
            ServerMessage.Mutable message = context.scheduler().getMessage();
            context.session().notifyResumed(message, (Boolean)request.getAttribute(HEARTBEAT_TIMEOUT_ATTRIBUTE));
            resume(context, message, Promise.from(y -> flush(context, promise), promise::fail));
        }
    }

    protected void process(Context context, Promise<Void> promise) {
        CometDRequest request = context.request();
        try {
            try {
                ServerMessage.Mutable[] messages = parseMessages(request);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Parsed {} messages", messages == null ? -1 : messages.length);
                }
                if (messages != null) {
                    processMessages(context, List.of(messages), promise);
                } else {
                    promise.succeed(null);
                }
            } catch (ParseException x) {
                LOGGER.warn("Could not parse JSON: " + x.getMessage(), x.getMessage());
                promise.fail(new HttpException(CometDResponse.SC_BAD_REQUEST, x.getCause()));
            }
        } catch (Throwable x) {
            promise.fail(x);
        }
    }

    @Override
    protected HttpScheduler suspend(Context context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Suspended {}", message);
        }
        CometDRequest request = context.request();
        context.scheduler(newHttpScheduler(context, promise, message, timeout));
        request.setAttribute(CONTEXT_ATTRIBUTE, context);
        context.session().notifySuspended(message, timeout);
        return context.scheduler();
    }

    protected HttpScheduler newHttpScheduler(Context context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
        return new DispatchingLongPollScheduler(context, promise, message, timeout);
    }

    protected abstract ServerMessage.Mutable[] parseMessages(CometDRequest request) throws IOException, ParseException;

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
                messages.addAll(List.of(parsed));
            }
        }
        return messages.toArray(new ServerMessage.Mutable[0]);
    }

    @Override
    protected void write(Context context, List<ServerMessage> messages, Promise<Void> promise) {
        CometDRequest request = context.request();
        CometDResponse response = context.response();
        try {
            ServerSessionImpl session = context.session();
            List<ServerMessage.Mutable> replies = context.replies();
            int replyIndex = 0;
            boolean needsComma = false;
            CometDOutput output;
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
                        writeMessage(context, output, reply);
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
                    writeMessage(context, output, message);
                }
            } finally {
                // Start the interval timeout after writing the messages
                // since they may take time to be written, even in case
                // of exceptions to make sure the session can be swept.
                if (context.scheduleExpiration()) {
                    scheduleExpiration(session, context.metaConnectCycle());
                }
            }

            // Write the replies, if any.
            while (replyIndex < replies.size()) {
                ServerMessage.Mutable reply = replies.get(replyIndex);
                if (needsComma) {
                    output.write(',');
                }
                needsComma = true;
                getBayeux().freeze(reply);
                writeMessage(context, output, reply);
                ++replyIndex;
            }

            endWrite(response, output);
            promise.succeed(null);
            writeComplete(context, messages);
        } catch (Throwable x) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Failure writing messages", x);
            }
            promise.fail(x);
        }
    }

    protected void writeMessage(Context context, CometDOutput output, ServerMessage message) throws IOException {
        writeMessage(context.response(), output, context.session(), message);
    }

    protected void writeMessage(CometDResponse response, CometDOutput output, ServerSessionImpl session, ServerMessage message) throws IOException {
        output.write(toJSONBytes(message));
    }

    protected abstract CometDOutput beginWrite(CometDRequest request, CometDResponse response) throws IOException;

    protected abstract void endWrite(CometDResponse response, CometDOutput output) throws IOException;

    protected void writeComplete(Context context, List<ServerMessage> messages) {
    }

    protected class DispatchingLongPollScheduler extends LongPollScheduler implements AsyncListener {
        public DispatchingLongPollScheduler(Context context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
            super(context, promise, message, timeout);
            AsyncContext asyncContext = getAsyncContext(context.request());
            if (asyncContext != null) {
                asyncContext.addListener(this);
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
        }

        @Override
        public void onTimeout(AsyncEvent event) {
        }

        @Override
        public void onError(AsyncEvent event) {
            error(event.getThrowable());
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
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
            CometDRequest request = getContext().request();
            request.setAttribute(HEARTBEAT_TIMEOUT_ATTRIBUTE, timeout);
            AsyncContext asyncContext = getAsyncContext(request);
            if (asyncContext != null) {
                asyncContext.dispatch();
            }
        }

        private AsyncContext getAsyncContext(CometDRequest request) {
            try {
                HttpServletRequest r = request.unwrap(HttpServletRequest.class);
                return r.getAsyncContext();
            } catch (Throwable x) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Could not retrieve AsyncContext for " + request, x);
                }
                return null;
            }
        }
    }
}
