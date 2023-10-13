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
package org.cometd.server.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.List;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.BufferingJSONAsyncParser;
import org.cometd.common.JSONContext;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDRequest;
import org.cometd.server.HttpException;
import org.cometd.server.JSONContextServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JSONHttpTransport extends AbstractHttpTransport {
    public static final String NAME = "long-polling";

    private static final Logger LOGGER = LoggerFactory.getLogger(JSONHttpTransport.class);
    private static final String PREFIX = "long-polling.json";

    public JSONHttpTransport(BayeuxServerImpl bayeux) {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public boolean accept(CometDRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void handle(TransportContext context) {
        CometDRequest request = context.request();
        String encoding = request.getCharacterEncoding();
        if (encoding == null) {
            encoding = "UTF-8";
        }

        AbstractReader reader;
        if ("UTF-8".equalsIgnoreCase(encoding)) {
            reader = new UTF8Reader(context);
        } else {
            Charset charset = Charset.forName(encoding);
            reader = new CharsetReader(context, charset);
        }
        CometDRequest.Input input = request.getInput();
        input.demand(reader::read);
    }

    private void process(TransportContext context, String json) {
        try {
            ServerMessage.Mutable[] messages = parseMessages(json);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Parsed {} messages", messages == null ? -1 : messages.length);
            }
            process(context, messages == null ? List.of() : List.of(messages));
        } catch (ParseException x) {
            LOGGER.warn("Could not parse JSON: " + x.getMessage(), x.getMessage());
            context.promise().fail(new HttpException(400, x.getCause()));
        } catch (Throwable x) {
            context.promise().fail(x);
        }
    }

    private void process(TransportContext context, List<ServerMessage.Mutable> messages) {
        try {
            processMessages(context, messages == null ? List.of() : messages);
        } catch (Throwable x) {
            context.promise().fail(x);
        }
    }

    private abstract class AbstractReader {
        private final TransportContext context;
        private int total;

        private AbstractReader(TransportContext context) {
            this.context = context;
        }

        private TransportContext context() {
            return context;
        }

        private void read() {
            try {
                onDataAvailable();
            } catch (Throwable x) {
                context().promise().fail(x);
            }
        }

        private void onDataAvailable() throws IOException {
            CometDRequest.Input input = context.request().getInput();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Asynchronous read start from {}", input);
            }
            long maxMessageSize = getMaxMessageSize();
            while (true) {
                CometDRequest.Input.Chunk chunk = input.read();
                if (chunk == null) {
                    input.demand(this::read);
                    return;
                }
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Asynchronous read {} from {}", chunk, input);
                }
                int read = chunk.byteBuffer().remaining();
                if (read > 0) {
                    if (maxMessageSize > 0) {
                        total += read;
                        if (total > maxMessageSize) {
                            throw new IOException("Max message size " + maxMessageSize + " exceeded");
                        }
                    }
                    processChunk(chunk);
                }
                chunk.release();
                if (chunk.isLast()) {
                    processEOF();
                    break;
                }
            }
        }

        private void processChunk(CometDRequest.Input.Chunk chunk) {
            try {
                onChunk(chunk);
            } catch (Throwable x) {
                throw new HttpException(400, x);
            }
        }

        private void processEOF() {
            try {
                onEOF();
            } catch (Throwable x) {
                throw new HttpException(400, x);
            }
        }

        protected abstract void onChunk(CometDRequest.Input.Chunk chunk);

        protected abstract void onEOF();

        protected void finish(List<ServerMessage.Mutable> messages) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Asynchronous read end from {}: {}", context.request().getInput(), messages);
            }
            process(context, messages);
        }

        protected void finish(String json) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Asynchronous read end from {}: {}", context.request().getInput(), json);
            }
            process(context, json);
        }
    }

    private class UTF8Reader extends AbstractReader {
        private final JSONContext.AsyncParser parser;

        private UTF8Reader(TransportContext context) {
            super(context);
            JSONContextServer jsonContext = getJSONContextServer();
            JSONContext.AsyncParser asyncParser = jsonContext.newAsyncParser();
            if (asyncParser == null) {
                asyncParser = new BufferingJSONAsyncParser(jsonContext);
            }
            this.parser = asyncParser;
        }

        @Override
        protected void onChunk(CometDRequest.Input.Chunk chunk) {
            parser.parse(chunk.byteBuffer());
        }

        @Override
        protected void onEOF() {
            List<ServerMessage.Mutable> messages = parser.complete();
            finish(messages);
        }
    }

    private class CharsetReader extends AbstractReader {
        private final Charset charset;
        private ByteBuffer aggregator = ByteBuffer.allocateDirect(256);

        private CharsetReader(TransportContext context, Charset charset) {
            super(context);
            this.charset = charset;
        }

        @Override
        protected void onChunk(CometDRequest.Input.Chunk chunk) {
            ByteBuffer byteBuffer = chunk.byteBuffer();
            int remaining = byteBuffer.remaining();
            if (aggregator.remaining() < remaining) {
                ByteBuffer newAggregator = ByteBuffer.allocateDirect(aggregator.position() + 2 * remaining);
                newAggregator.put(aggregator.flip());
                aggregator = newAggregator;
            }
            aggregator.put(byteBuffer);
        }

        @Override
        protected void onEOF() {
            String json = charset.decode(aggregator.flip()).toString();
            finish(json);
        }
    }
}
