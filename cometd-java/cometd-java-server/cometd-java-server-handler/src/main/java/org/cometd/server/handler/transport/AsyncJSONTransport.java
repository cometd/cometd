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
package org.cometd.server.handler.transport;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.BufferingJSONAsyncParser;
import org.cometd.common.JSONContext;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.JSONContextServer;
import org.cometd.server.http.AbstractHttpTransport;
import org.cometd.server.http.TransportContext;
import org.cometd.server.spi.CometDInput;
import org.cometd.server.spi.CometDOutput;
import org.cometd.server.spi.CometDRequest;
import org.cometd.server.spi.CometDResponse;
import org.cometd.server.spi.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncJSONTransport extends AbstractHttpTransport
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncJSONTransport.class);
    private static final String PREFIX = "long-polling.json";
    private static final String NAME = "long-polling";
    private static final int BUFFER_CAPACITY = 512;
    private static final ThreadLocal<byte[]> buffers = ThreadLocal.withInitial(() -> new byte[BUFFER_CAPACITY]);

    public AsyncJSONTransport(BayeuxServerImpl bayeux) {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public boolean accept(CometDRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    public void handle(BayeuxContext bayeuxContext, CometDRequest request, CometDResponse response, Promise<Void> p) throws IOException {
        String encoding = request.getCharacterEncoding();
        if (encoding == null) {
            encoding = "UTF-8";
        }
        request.setCharacterEncoding(encoding);

        Promise<Void> promise = new Promise<>() {
            @Override
            public void succeed(Void result) {
                p.succeed(result);
            }

            @Override
            public void fail(Throwable failure) {
                int code = failure instanceof TimeoutException ?
                    getDuplicateMetaConnectHttpResponseCode() :
                    CometDResponse.SC_INTERNAL_SERVER_ERROR;
                p.fail(new HttpException(code, failure));
            }
        };

        TransportContext context = new TransportContext(bayeuxContext, request, response);

        Charset charset = Charset.forName(encoding);
        AbstractReader reader = "UTF-8".equals(charset.name()) ?
                new UTF8Reader(context, promise) :
                new CharsetReader(context, promise, charset);
        CometDInput input = request.getInput();
        input.demand(reader::nextRead);
    }

    protected void process(String json, TransportContext context, Promise<Void> promise) {
        try {
            try {
                ServerMessage.Mutable[] messages = parseMessages(json);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Parsed {} messages", messages == null ? -1 : messages.length);
                }
                process(messages == null ? null : List.of(messages), context, promise);
            } catch (ParseException x) {
                LOGGER.warn("Could not parse JSON: " + x.getMessage(), x.getMessage());
                promise.fail(new HttpException(CometDResponse.SC_BAD_REQUEST, x.getCause()));
            }
        } catch (Throwable x) {
            promise.fail(x);
        }
    }

    private void process(List<ServerMessage.Mutable> messages, TransportContext context, Promise<Void> promise) {
        try {
            if (messages != null) {
                processMessages(context, messages, promise);
            } else {
                promise.succeed(null);
            }
        } catch (Throwable x) {
            promise.fail(x);
        }
    }

    @Override
    protected HttpScheduler suspend(TransportContext context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Suspended {}", message);
        }
        context.scheduler(newHttpScheduler(context, promise, message, timeout));
        context.session().notifySuspended(message, timeout);
        return context.scheduler();
    }

    protected HttpScheduler newHttpScheduler(TransportContext context, Promise<Void> promise, ServerMessage.Mutable reply, long timeout) {
        return new AsyncLongPollScheduler(context, promise, reply, timeout);
    }

    @Override
    protected void write(TransportContext context, List<ServerMessage> messages, Promise<Void> promise) {
        CometDResponse response = context.response();
        try {
            // Always write asynchronously
            response.setContentType("application/json;charset=UTF-8");
            Writer writer = new Writer(context, messages, promise);
            writer.nextWrite();
        } catch (Throwable x) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Exception while writing messages", x);
            }
            if (context.scheduleExpiration()) {
                scheduleExpiration(context.session(), context.metaConnectCycle());
            }
            promise.fail(x);
        }
    }

    protected void writeComplete(TransportContext context, List<ServerMessage> messages) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Messages/replies {}/{} written for {}", messages.size(), context.replies().size(), context.session());
        }
    }

    protected abstract class AbstractReader {
        private final TransportContext context;
        private final Promise<Void> promise;
        private int total;

        protected AbstractReader(TransportContext context, Promise<Void> promise) {
            this.context = context;
            this.promise = promise;
        }

        private void nextRead() {
            try {
                onDataAvailable();
            } catch (IOException e) {
                promise.fail(e);
            }
        }

        private void onDataAvailable() throws IOException {
            CometDInput input = context.request().getInput();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Asynchronous read start from {}", input);
            }
            long maxMessageSize = getMaxMessageSize();
            byte[] buffer = buffers.get();
            while (true) {
                int read = input.read(buffer);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Asynchronous read {} bytes from {}", read, input);
                }
                if (read < 0) {
                    onAllDataRead();
                    break;
                } else if (read == 0) {
                    input.demand(this::nextRead);
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
        }

        protected abstract void onAllDataRead();

        protected abstract void append(byte[] buffer, int offset, int length);

        protected void finish(List<ServerMessage.Mutable> messages) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Asynchronous read end from {}: {}", context.request().getInput(), messages);
            }
            process(messages, context, promise);
        }

        protected void finish(String json) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Asynchronous read end from {}: {}", context.request().getInput(), json);
            }
            process(json, context, promise);
        }
    }

    protected class UTF8Reader extends AbstractReader {
        private final JSONContext.AsyncParser parser;

        protected UTF8Reader(TransportContext context, Promise<Void> promise) {
            super(context, promise);
            JSONContextServer jsonContext = getJSONContextServer();
            JSONContext.AsyncParser asyncParser = jsonContext.newAsyncParser();
            if (asyncParser == null) {
                asyncParser = new BufferingJSONAsyncParser(jsonContext);
            }
            this.parser = asyncParser;
        }

        @Override
        protected void append(byte[] bytes, int offset, int length) {
            parser.parse(bytes, offset, length);
        }

        @Override
        public void onAllDataRead() {
            List<ServerMessage.Mutable> messages = parser.complete();
            finish(messages);
        }
    }

    protected class CharsetReader extends AbstractReader {
        private byte[] content = new byte[BUFFER_CAPACITY];
        private final Charset charset;
        private int count;

        public CharsetReader(TransportContext context, Promise<Void> promise, Charset charset) {
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
        public void onAllDataRead() {
            finish(new String(content, 0, count, charset));
        }
    }

    protected class Writer {
        private final TransportContext context;
        private final List<ServerMessage> messages;
        private final Promise<Void> promise;
        private int messageIndex;
        private int replyIndex;
        private boolean needsComma;
        private State state = State.BEGIN;
        private final SerializedInvoker invoker = new SerializedInvoker();

        protected Writer(TransportContext context, List<ServerMessage> messages, Promise<Void> p) {
            this.context = context;
            this.messages = messages;
            this.promise = new Promise<>() {
                @Override
                public void succeed(Void result)
                {
                    if (state == State.COMPLETE) {
                        p.succeed(result);
                    } else {
                        nextWrite();
                    }
                }

                @Override
                public void fail(Throwable failure)
                {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Failure writing messages", failure);
                    }
                    // Start the interval timeout also in case of
                    // errors to ensure the session can be swept.
                    startExpiration();
                    p.fail(failure);
                }
            };
        }

        private void nextWrite() {
            try {
                invoker.run(this::onWritePossible);
            } catch (Throwable x) {
                promise.fail(x);
            }
        }

        private void onWritePossible() {
            CometDOutput output = context.response().getOutput();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Messages/replies {}/{} to write for {}", messages.size(), context.replies().size(), context.session());
            }

            while (true) {
                State current = state;
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Current state: {}", current);
                }
                switch (current) {
                    case BEGIN -> {
                        state = State.HANDSHAKE;
                        writeBegin(output);
                        return;
                    }
                    case HANDSHAKE -> {
                        state = State.MESSAGES;
                        if (!writeHandshakeReply(output)) {
                            return;
                        }
                    }
                    case MESSAGES -> {
                        if (!writeMessages(output)) {
                            return;
                        }
                        state = State.REPLIES;
                    }
                    case REPLIES -> {
                        if (!writeReplies(output)) {
                            return;
                        }
                        state = State.END;
                    }
                    case END -> {
                        state = State.COMPLETE;
                        writeEnd(output);
                        return;
                    }
                    case COMPLETE -> {
                        promise.succeed(null);
                        writeComplete(context, messages);
                        return;
                    }
                    default -> {
                        throw new IllegalStateException("Could not write in state " + current);
                    }
                }
            }
        }

        private void writeBegin(CometDOutput output) {
            output.write('[', promise);
        }

        private boolean writeHandshakeReply(CometDOutput output) {
            List<ServerMessage.Mutable> replies = context.replies();
            if (replies.size() > 0) {
                ServerMessage.Mutable reply = replies.get(0);
                if (Channel.META_HANDSHAKE.equals(reply.getChannel())) {
                    if (allowMessageDeliveryDuringHandshake(context.session()) && !messages.isEmpty()) {
                        reply.put("x-messages", messages.size());
                    }
                    getBayeux().freeze(reply);
                    output.write(toJSONBytes(reply), promise);
                    needsComma = true;
                    ++replyIndex;
                    return false;
                }
            }
            return true;
        }

        private boolean writeMessages(CometDOutput output) {
            int size = messages.size();
            if (messageIndex == size) {
                // Start the interval timeout after writing the
                // messages since they may take time to be written.
                startExpiration();
                return true;
            } else {
                if (needsComma) {
                    needsComma = false;
                    output.write(',', promise);
                } else {
                    ServerMessage message = messages.get(messageIndex);
                    needsComma = true;
                    ++messageIndex;
                    output.write(toJSONBytes(message), promise);
                }
                return false;
            }
        }

        private void startExpiration() {
            if (context.scheduleExpiration()) {
                scheduleExpiration(context.session(), context.metaConnectCycle());
            }
        }

        private boolean writeReplies(CometDOutput output) {
            List<ServerMessage.Mutable> replies = context.replies();
            int size = replies.size();
            if (replyIndex == size) {
                return true;
            } else {
                ServerMessage.Mutable reply = replies.get(replyIndex);
                if (needsComma) {
                    needsComma = false;
                    output.write(',', promise);
                } else {
                    getBayeux().freeze(reply);
                    needsComma = replyIndex < size;
                    ++replyIndex;
                    output.write(toJSONBytes(reply), promise);
                }
                return false;
            }
        }

        private void writeEnd(CometDOutput output) {
            output.write(']', promise);
        }
    }

    private enum State {
        BEGIN, HANDSHAKE, MESSAGES, REPLIES, END, COMPLETE
    }

    private class AsyncLongPollScheduler extends LongPollScheduler
    {
        private AsyncLongPollScheduler(TransportContext context, Promise<Void> promise, ServerMessage.Mutable reply, long timeout) {
            super(context, promise, reply, timeout);
        }

        @Override
        protected void dispatch(boolean timeout) {
            // Directly succeeding the callback to write messages and replies.
            // Since the write is async, we will never block and thus never delay other sessions.
            getContext().session().notifyResumed(getMessage(), timeout);
            getPromise().succeed(null);
        }
    }
}
