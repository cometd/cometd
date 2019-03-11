/*
 * Copyright (c) 2008-2019 the original author or authors.
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
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.util.Utf8StringBuilder;

public class AsyncJSONTransport extends AbstractHttpTransport {
    private static final String PREFIX = "long-polling.json";
    private static final String NAME = "long-polling";
    private static final int BUFFER_CAPACITY = 512;
    private static final ThreadLocal<byte[]> buffers = ThreadLocal.withInitial(() -> new byte[BUFFER_CAPACITY]);

    public AsyncJSONTransport(BayeuxServerImpl bayeux) {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public boolean accept(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        String encoding = request.getCharacterEncoding();
        if (encoding == null) {
            encoding = "UTF-8";
        }
        request.setCharacterEncoding(encoding);
        AsyncContext asyncContext = request.startAsync();
        // Explicitly disable the timeout, to prevent
        // that the timeout fires in case of slow reads.
        asyncContext.setTimeout(0);

        Promise<Void> promise = new Promise<Void>() {
            public void succeed(Void result) {
                asyncContext.complete();
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Handling successful");
                }
            }

            @Override
            public void fail(Throwable failure) {
                request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
                int code = failure instanceof TimeoutException ?
                        HttpServletResponse.SC_REQUEST_TIMEOUT :
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                sendError(request, response, code, failure);
                asyncContext.complete();
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Handling failed", failure);
                }
            }
        };

        Context context = new Context(request, response);

        Charset charset = Charset.forName(encoding);
        ReadListener reader = "UTF-8".equals(charset.name()) ?
                new UTF8Reader(context, promise) :
                new CharsetReader(context, promise, charset);
        ServletInputStream input = request.getInputStream();
        input.setReadListener(reader);
    }

    protected void process(String json, Context context, Promise<Void> promise) {
        try {
            try {
                ServerMessage.Mutable[] messages = parseMessages(json);
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Parsed {} messages", messages == null ? -1 : messages.length);
                }
                if (messages != null) {
                    processMessages(context, messages, promise);
                } else {
                    promise.succeed(null);
                }
            } catch (ParseException x) {
                handleJSONParseException(context.request, context.response, json, x);
                promise.succeed(null);
            }
        } catch (Throwable x) {
            promise.fail(x);
        }
    }

    @Override
    protected HttpScheduler suspend(Context context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Suspended {}", message);
        }
        context.scheduler = newHttpScheduler(context, promise, message, timeout);
        context.session.notifySuspended(message, timeout);
        return context.scheduler;
    }

    protected HttpScheduler newHttpScheduler(Context context, Promise<Void> promise, ServerMessage.Mutable reply, long timeout) {
        return new AsyncLongPollScheduler(context, promise, reply, timeout);
    }

    @Override
    protected void write(Context context, List<ServerMessage> messages, Promise<Void> promise) {
        HttpServletResponse response = context.response;
        try {
            // Always write asynchronously
            response.setContentType("application/json;charset=UTF-8");
            ServletOutputStream output = response.getOutputStream();
            output.setWriteListener(new Writer(context, messages, promise));
        } catch (Throwable x) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Exception while writing messages", x);
            }
            if (context.scheduleExpiration) {
                scheduleExpiration(context.session);
            }
            promise.fail(x);
        }
    }

    protected void writeComplete(Context context, List<ServerMessage> messages) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Messages/replies {}/{} written for session {}", messages.size(), context.replies.size(), context.session);
        }
    }

    protected abstract class AbstractReader implements ReadListener {
        private final Context context;
        private final Promise<Void> promise;
        private int total;

        protected AbstractReader(Context context, Promise<Void> promise) {
            this.context = context;
            this.promise = promise;
        }

        @Override
        public void onDataAvailable() throws IOException {
            ServletInputStream input = context.request.getInputStream();
            if (_logger.isDebugEnabled()) {
                _logger.debug("Asynchronous read start from {}", input);
            }
            int maxMessageSize = getMaxMessageSize();
            byte[] buffer = buffers.get();
            while (input.isReady()) {
                int read = input.read(buffer);
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Asynchronous read {} bytes from {}", read, input);
                }
                if (read < 0) {
                    break;
                } else {
                    if (maxMessageSize > 0) {
                        total += read;
                        if (total > maxMessageSize) {
                            throw new IOException("Max message size " + maxMessageSize + " exceeded");
                        }
                    }
                    append(buffer, 0, read);
                }
            }
            if (!input.isFinished()) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Asynchronous read pending from {}", input);
                }
            }
        }

        protected abstract void append(byte[] buffer, int offset, int length);

        @Override
        public void onAllDataRead() throws IOException {
            ServletInputStream input = context.request.getInputStream();
            String json = finish();
            if (_logger.isDebugEnabled()) {
                _logger.debug("Asynchronous read end from {}: {}", input, json);
            }
            process(json, context, promise);
        }

        protected abstract String finish();

        @Override
        public void onError(Throwable failure) {
            promise.fail(failure);
        }
    }

    protected class UTF8Reader extends AbstractReader {
        private final Utf8StringBuilder content = new Utf8StringBuilder(BUFFER_CAPACITY);

        protected UTF8Reader(Context context, Promise<Void> promise) {
            super(context, promise);
        }

        @Override
        protected void append(byte[] buffer, int offset, int length) {
            content.append(buffer, offset, length);
        }

        @Override
        protected String finish() {
            return content.toString();
        }
    }

    protected class CharsetReader extends AbstractReader {
        private byte[] content = new byte[BUFFER_CAPACITY];
        private final Charset charset;
        private int count;

        public CharsetReader(Context context, Promise<Void> promise, Charset charset) {
            super(context, promise);
            this.charset = charset;
        }

        @Override
        protected void append(byte[] buffer, int offset, int length) {
            int size = content.length;
            int newSize = size;
            while (newSize - count < length) {
                newSize <<= 1;
            }

            if (newSize < 0) {
                throw new IllegalArgumentException("Message too large");
            }

            if (newSize != size) {
                byte[] newContent = new byte[newSize];
                System.arraycopy(content, 0, newContent, 0, count);
                content = newContent;
            }

            System.arraycopy(buffer, offset, content, count, length);
            count += length;
        }

        @Override
        protected String finish() {
            return new String(content, 0, count, charset);
        }
    }

    protected class Writer implements WriteListener {
        private final Context context;
        private final List<ServerMessage> messages;
        private final Promise<Void> promise;
        private int messageIndex;
        private int replyIndex;
        private boolean needsComma;
        private State state = State.BEGIN;

        protected Writer(Context context, List<ServerMessage> messages, Promise<Void> promise) {
            this.context = context;
            this.messages = messages;
            this.promise = promise;
        }

        @Override
        public void onWritePossible() throws IOException {
            ServletOutputStream output = context.response.getOutputStream();

            if (_logger.isDebugEnabled()) {
                _logger.debug("Messages/replies {}/{} to write for session {}", messages.size(), context.replies.size(), context.session);
            }

            while (true) {
                switch (state) {
                    case BEGIN: {
                        state = State.HANDSHAKE;
                        if (!writeBegin(output)) {
                            return;
                        }
                        break;
                    }
                    case HANDSHAKE: {
                        state = State.MESSAGES;
                        if (!writeHandshakeReply(output)) {
                            return;
                        }
                        break;
                    }
                    case MESSAGES: {
                        if (!writeMessages(output)) {
                            return;
                        }
                        state = State.REPLIES;
                        break;
                    }
                    case REPLIES: {
                        if (!writeReplies(output)) {
                            return;
                        }
                        state = State.END;
                        break;
                    }
                    case END: {
                        state = State.COMPLETE;
                        if (!writeEnd(output)) {
                            return;
                        }
                        break;
                    }
                    case COMPLETE: {
                        promise.succeed(null);
                        writeComplete(context, messages);
                        return;
                    }
                    default: {
                        throw new IllegalStateException();
                    }
                }
            }
        }

        private boolean writeBegin(ServletOutputStream output) throws IOException {
            output.write('[');
            return output.isReady();
        }

        private boolean writeHandshakeReply(ServletOutputStream output) throws IOException {
            List<ServerMessage.Mutable> replies = context.replies;
            if (replies.size() > 0) {
                ServerMessage.Mutable reply = replies.get(0);
                if (Channel.META_HANDSHAKE.equals(reply.getChannel())) {
                    if (allowMessageDeliveryDuringHandshake(context.session) && !messages.isEmpty()) {
                        reply.put("x-messages", messages.size());
                    }
                    getBayeux().freeze(reply);
                    output.write(toJSONBytes(reply, "UTF-8"));
                    needsComma = true;
                    ++replyIndex;
                }
            }
            return output.isReady();
        }

        private boolean writeMessages(ServletOutputStream output) throws IOException {
            try {
                int size = messages.size();
                while (output.isReady()) {
                    if (messageIndex == size) {
                        // Start the interval timeout after writing the
                        // messages since they may take time to be written.
                        startExpiration();
                        return true;
                    } else {
                        if (needsComma) {
                            output.write(',');
                            needsComma = false;
                        } else {
                            ServerMessage message = messages.get(messageIndex);
                            output.write(toJSONBytes(message, "UTF-8"));
                            needsComma = messageIndex < size;
                            ++messageIndex;
                        }
                    }
                }
                return false;
            } catch (Throwable x) {
                // Start the interval timeout also in case of
                // exceptions to ensure the session can be swept.
                startExpiration();
                throw x;
            }
        }

        private void startExpiration() {
            if (context.scheduleExpiration) {
                scheduleExpiration(context.session);
            }
        }

        private boolean writeReplies(ServletOutputStream output) throws IOException {
            List<ServerMessage.Mutable> replies = context.replies;
            int size = replies.size();
            while (output.isReady()) {
                if (replyIndex == size) {
                    return true;
                } else {
                    ServerMessage.Mutable reply = replies.get(replyIndex);
                    if (needsComma) {
                        output.write(',');
                        needsComma = false;
                    } else {
                        getBayeux().freeze(reply);
                        output.write(toJSONBytes(reply, "UTF-8"));
                        needsComma = replyIndex < size;
                        ++replyIndex;
                    }
                }
            }
            return false;
        }

        private boolean writeEnd(ServletOutputStream output) throws IOException {
            output.write(']');
            return output.isReady();
        }

        @Override
        public void onError(Throwable failure) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Failure writing messages", failure);
            }
            // Start the interval timeout also in case of
            // errors to ensure the session can be swept.
            startExpiration();
            promise.fail(failure);
        }
    }

    private enum State {
        BEGIN, HANDSHAKE, MESSAGES, REPLIES, END, COMPLETE
    }

    private class AsyncLongPollScheduler extends LongPollScheduler {
        private AsyncLongPollScheduler(Context context, Promise<Void> promise, ServerMessage.Mutable reply, long timeout) {
            super(context, promise, reply, timeout);
        }

        @Override
        protected void dispatch(boolean timeout) {
            // Directly succeeding the callback to write messages and replies.
            // Since the write is async, we will never block and thus never delay other sessions.
            getContext().session.notifyResumed(getMessage(), timeout);
            getPromise().succeed(null);
        }
    }
}
