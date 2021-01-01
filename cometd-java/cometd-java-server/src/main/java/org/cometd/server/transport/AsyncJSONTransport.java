/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.util.Utf8StringBuilder;

public class AsyncJSONTransport extends AbstractHttpTransport {
    private static final String PREFIX = "long-polling.json";
    private static final String NAME = "long-polling";
    private static final int BUFFER_CAPACITY = 512;
    private static final ThreadLocal<byte[]> buffers = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[BUFFER_CAPACITY];
        }
    };

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
        AsyncContext asyncContext = request.startAsync(request, response);
        // Explicitly disable the timeout, to prevent
        // that the timeout fires in case of slow reads.
        asyncContext.setTimeout(0);
        Charset charset = Charset.forName(encoding);
        ReadListener reader = "UTF-8".equals(charset.name()) ? new UTF8Reader(request, response, asyncContext) :
                new CharsetReader(request, response, asyncContext, charset);
        ServletInputStream input = request.getInputStream();
        input.setReadListener(reader);
    }

    @Override
    protected HttpScheduler suspend(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable reply, long timeout) {
        return newHttpScheduler(request, response, getAsyncContext(request), session, reply, timeout);
    }

    protected HttpScheduler newHttpScheduler(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, long timeout) {
        return new AsyncLongPollScheduler(request, response, asyncContext, session, reply, timeout);
    }

    @Override
    protected void write(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, boolean scheduleExpiration, List<ServerMessage> messages, ServerMessage.Mutable[] replies) {
        AsyncContext asyncContext = getAsyncContext(request);
        try {
            // Always write asynchronously
            response.setContentType("application/json;charset=UTF-8");
            ServletOutputStream output = response.getOutputStream();
            output.setWriteListener(new Writer(request, response, asyncContext, session, scheduleExpiration, messages, replies));
        } catch (Exception x) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Exception while writing messages", x);
            }
            if (scheduleExpiration) {
                scheduleExpiration(session);
            }
            error(request, response, asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected void writeComplete(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, List<ServerMessage> messages, ServerMessage.Mutable[] replies) {
    }

    protected abstract class AbstractReader implements ReadListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        protected final AsyncContext asyncContext;
        private int total;

        protected AbstractReader(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext) {
            this.request = request;
            this.response = response;
            this.asyncContext = asyncContext;
        }

        @Override
        public void onDataAvailable() throws IOException {
            ServletInputStream input = request.getInputStream();
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
            ServletInputStream input = request.getInputStream();
            String json = finish();
            if (_logger.isDebugEnabled()) {
                _logger.debug("Asynchronous read end from {}: {}", input, json);
            }
            process(json);
        }

        protected abstract String finish();

        protected void process(String json) throws IOException {
            getBayeux().setCurrentTransport(AsyncJSONTransport.this);
            setCurrentRequest(request);
            try {
                ServerMessage.Mutable[] messages = parseMessages(json);
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Parsed {} messages", messages == null ? -1 : messages.length);
                }
                if (messages != null) {
                    processMessages(request, response, messages);
                } else {
                    asyncContext.complete();
                }
            } catch (ParseException x) {
                handleJSONParseException(request, response, json, x);
                asyncContext.complete();
            } finally {
                setCurrentRequest(null);
                getBayeux().setCurrentTransport(null);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            error(request, response, asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected class UTF8Reader extends AbstractReader {
        private final Utf8StringBuilder content = new Utf8StringBuilder(BUFFER_CAPACITY);

        protected UTF8Reader(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext) {
            super(request, response, asyncContext);
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

        public CharsetReader(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, Charset charset) {
            super(request, response, asyncContext);
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
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final AsyncContext asyncContext;
        private final ServerSessionImpl session;
        private final boolean scheduleExpiration;
        private final List<ServerMessage> messages;
        private final ServerMessage.Mutable[] replies;
        private int messageIndex;
        private int replyIndex;
        private boolean needsComma;
        private State state = State.BEGIN;

        protected Writer(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, boolean scheduleExpiration, List<ServerMessage> messages, ServerMessage.Mutable[] replies) {
            this.request = request;
            this.response = response;
            this.asyncContext = asyncContext;
            this.session = session;
            this.scheduleExpiration = scheduleExpiration;
            this.messages = messages;
            this.replies = replies;
        }

        @Override
        public void onWritePossible() throws IOException {
            ServletOutputStream output = response.getOutputStream();

            if (_logger.isDebugEnabled()) {
                _logger.debug("Messages/replies {}/{} to write for session {}", messages.size(), replies.length, session);
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
                        if (asyncContext != null) {
                            asyncContext.complete();
                        }
                        writeComplete(request, response, session, messages, replies);
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
            if (replies.length > 0) {
                ServerMessage.Mutable reply = replies[0];
                if (Channel.META_HANDSHAKE.equals(reply.getChannel())) {
                    if (allowMessageDeliveryDuringHandshake(session) && !messages.isEmpty()) {
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
            if (scheduleExpiration) {
                scheduleExpiration(session);
            }
        }

        private boolean writeReplies(ServletOutputStream output) throws IOException {
            int size = replies.length;
            while (output.isReady()) {
                if (replyIndex == size) {
                    return true;
                } else {
                    ServerMessage.Mutable reply = replies[replyIndex];
                    if (reply != null) {
                        if (needsComma) {
                            output.write(',');
                            needsComma = false;
                        } else {
                            getBayeux().freeze(reply);
                            output.write(toJSONBytes(reply, "UTF-8"));
                            needsComma = replyIndex < size;
                            ++replyIndex;
                        }
                    } else {
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
        public void onError(Throwable throwable) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("Failure writing messages", throwable);
            }
            // Start the interval timeout also in case of
            // errors to ensure the session can be swept.
            startExpiration();
            error(request, response, asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private enum State {
        BEGIN, HANDSHAKE, MESSAGES, REPLIES, END, COMPLETE
    }

    private class AsyncLongPollScheduler extends LongPollScheduler {
        private AsyncLongPollScheduler(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, long timeout) {
            super(request, response, asyncContext, session, reply, timeout);
        }

        @Override
        protected void dispatch() {
            // Direct call to resume() to write the messages in the queue and the replies.
            // Since the write is async, we will never block here and thus never delay other sessions.
            resume(getRequest(), getResponse(), getAsyncContext(), getServerSession(), getMetaConnectReply());
        }
    }
}
