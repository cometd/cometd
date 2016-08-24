/*
 * Copyright (c) 2008-2016 the original author or authors.
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

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.util.Utf8StringBuilder;

public class AsyncJSONTransport extends AbstractHttpTransport
{
    private static final String PREFIX = "long-polling.json";
    private static final String NAME = "long-polling";
    private static final int BUFFER_CAPACITY = 512;
    private static final ThreadLocal<byte[]> buffers = new ThreadLocal<byte[]>()
    {
        @Override
        protected byte[] initialValue()
        {
            return new byte[BUFFER_CAPACITY];
        }
    };

    public AsyncJSONTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux, NAME);
        setOptionPrefix(PREFIX);
    }

    @Override
    public boolean accept(HttpServletRequest request)
    {
        return "POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        String encoding = request.getCharacterEncoding();
        if (encoding == null)
            encoding = "UTF-8";
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

    protected HttpScheduler suspend(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId, long timeout)
    {
        AsyncContext asyncContext = request.getAsyncContext();
        return newHttpScheduler(request, response, asyncContext, session, reply, browserId, timeout);
    }

    protected HttpScheduler newHttpScheduler(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId, long timeout)
    {
        return new AsyncLongPollScheduler(request, response, asyncContext, session, reply, browserId, timeout);
    }

    @Override
    protected void write(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, boolean startInterval, List<ServerMessage> messages, ServerMessage.Mutable[] replies)
    {
        AsyncContext asyncContext = request.getAsyncContext();
        try
        {
            // Always write asynchronously
            response.setContentType("application/json;charset=UTF-8");
            ServletOutputStream output = response.getOutputStream();
            output.setWriteListener(new Writer(request, response, asyncContext, session, startInterval, messages, replies));
        }
        catch (Exception x)
        {
            if (_logger.isDebugEnabled())
                _logger.debug("Exception while writing messages", x);
            error(request, response, asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected abstract class AbstractReader implements ReadListener
    {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        protected final AsyncContext asyncContext;

        protected AbstractReader(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext)
        {
            this.request = request;
            this.response = response;
            this.asyncContext = asyncContext;
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            ServletInputStream input = request.getInputStream();
            if (_logger.isDebugEnabled())
                _logger.debug("Asynchronous read start from {}", input);
            byte[] buffer = buffers.get();
            // First check for isReady() because it has
            // side effects, and then for isFinished().
            while (input.isReady() && !input.isFinished())
            {
                int read = input.read(buffer);
                if (_logger.isDebugEnabled())
                    _logger.debug("Asynchronous read {} bytes from {}", read, input);
                if (read >= 0)
                    append(buffer, 0, read);
            }
            if (!input.isFinished())
                if (_logger.isDebugEnabled())
                    _logger.debug("Asynchronous read pending from {}", input);
        }

        protected abstract void append(byte[] buffer, int offset, int length);

        @Override
        public void onAllDataRead() throws IOException
        {
            ServletInputStream input = request.getInputStream();
            String json = finish();
            if (_logger.isDebugEnabled())
                _logger.debug("Asynchronous read end from {}: {}", input, json);
            process(json);
        }

        protected abstract String finish();

        protected void process(String json) throws IOException
        {
            getBayeux().setCurrentTransport(AsyncJSONTransport.this);
            setCurrentRequest(request);
            try
            {
                ServerMessage.Mutable[] messages = parseMessages(json);
                if (_logger.isDebugEnabled())
                    _logger.debug("Parsed {} messages", messages == null ? -1 : messages.length);
                if (messages != null)
                    processMessages(request, response, messages);
                else
                    asyncContext.complete();
            }
            catch (ParseException x)
            {
                handleJSONParseException(request, response, json, x);
                asyncContext.complete();
            }
            finally
            {
                setCurrentRequest(null);
                getBayeux().setCurrentTransport(null);
            }
        }

        @Override
        public void onError(Throwable throwable)
        {
            error(request, response, asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected class UTF8Reader extends AbstractReader
    {
        private final Utf8StringBuilder content = new Utf8StringBuilder(BUFFER_CAPACITY);

        protected UTF8Reader(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext)
        {
            super(request, response, asyncContext);
        }

        @Override
        protected void append(byte[] buffer, int offset, int length)
        {
            content.append(buffer, offset, length);
        }

        @Override
        protected String finish()
        {
            return content.toString();
        }
    }

    protected class CharsetReader extends AbstractReader
    {
        private byte[] content = new byte[BUFFER_CAPACITY];
        private final Charset charset;
        private int count;

        public CharsetReader(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, Charset charset)
        {
            super(request, response, asyncContext);
            this.charset = charset;
        }

        @Override
        protected void append(byte[] buffer, int offset, int length)
        {
            int size = content.length;
            int newSize = size;
            while (newSize - count < length)
                newSize <<= 1;

            if (newSize < 0)
                throw new IllegalArgumentException("Message too large");

            if (newSize != size)
            {
                byte[] newContent = new byte[newSize];
                System.arraycopy(content, 0, newContent, 0, count);
                content = newContent;
            }

            System.arraycopy(buffer, offset, content, count, length);
            count += length;
        }

        @Override
        protected String finish()
        {
            return new String(content, 0, count, charset);
        }
    }

    protected class Writer implements WriteListener
    {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final AsyncContext asyncContext;
        private final ServerSessionImpl session;
        private final boolean startInterval;
        private final List<ServerMessage> messages;
        private final ServerMessage.Mutable[] replies;
        private int messageIndex = -1;
        private int replyIndex = -1;
        private boolean needsComma;

        protected Writer(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, boolean startInterval, List<ServerMessage> messages, ServerMessage.Mutable[] replies)
        {
            this.request = request;
            this.response = response;
            this.asyncContext = asyncContext;
            this.session = session;
            this.startInterval = startInterval;
            this.messages = messages;
            this.replies = replies;
        }

        @Override
        public void onWritePossible() throws IOException
        {
            ServletOutputStream output = response.getOutputStream();

            if (_logger.isDebugEnabled())
                _logger.debug("Messages to write for session {}: {}", session, messages.size());
            if (!writeMessages(output))
                return;

            if (_logger.isDebugEnabled())
                _logger.debug("Replies to write for session {}: {}", session, replies.length);
            if (!writeReplies(output))
                return;

            asyncContext.complete();
        }

        private boolean writeMessages(ServletOutputStream output) throws IOException
        {
            try
            {
                int size = messages.size();
                while (output.isReady())
                {
                    if (messageIndex < 0)
                    {
                        messageIndex = 0;
                        output.write('[');
                    }
                    else
                    {
                        if (messageIndex == size)
                        {
                            // Start the interval timeout after writing the
                            // messages since they may take time to be written.
                            startInterval();
                            return true;
                        }
                        else
                        {
                            if (needsComma)
                            {
                                needsComma = false;
                                output.write(',');
                            }
                            else
                            {
                                ServerMessage message = messages.get(messageIndex);
                                output.write(toJSONBytes(message, "UTF-8"));
                                ++messageIndex;
                                needsComma = messageIndex < size;
                            }
                        }
                    }
                }
                return false;
            }
            catch (Throwable x)
            {
                // Start the interval timeout also in case of
                // exceptions to ensure the session can be swept.
                startInterval();
                throw x;
            }
        }

        private void startInterval()
        {
            if (startInterval && session != null && session.isConnected())
                session.startIntervalTimeout(getInterval());
        }

        private boolean writeReplies(ServletOutputStream output) throws IOException
        {
            int size = replies.length;
            while (output.isReady())
            {
                if (replyIndex < 0)
                {
                    replyIndex = 0;
                    needsComma = messageIndex > 0;
                }
                else if (replyIndex < size)
                {
                    ServerMessage.Mutable reply = replies[replyIndex];
                    if (reply != null)
                    {
                        if (needsComma)
                        {
                            needsComma = false;
                            output.write(',');
                        }
                        else
                        {
                            output.write(toJSONBytes(reply, "UTF-8"));
                            ++replyIndex;
                            needsComma = replyIndex < size;
                        }
                    }
                    else
                    {
                        ++replyIndex;
                    }
                }
                else if (replyIndex == size)
                {
                    ++replyIndex;
                    output.write(']');
                }
                else
                {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onError(Throwable throwable)
        {
            // Start the interval timeout also in case of
            // errors to ensure the session can be swept.
            startInterval();
            error(request, response, asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private class AsyncLongPollScheduler extends LongPollScheduler
    {
        private AsyncLongPollScheduler(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId, long timeout)
        {
            super(request, response, asyncContext, session, reply, browserId, timeout);
        }

        @Override
        protected void dispatch()
        {
            // Direct call to resume() to write the messages in the queue and the replies.
            // Since the write is async, we will never block here and thus never delay other sessions.
            resume(getRequest(), getResponse(), getAsyncContext(), getServerSession(), getMetaConnectReply());
        }
    }
}
