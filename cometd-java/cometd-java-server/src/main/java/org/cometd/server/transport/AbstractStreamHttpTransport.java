/*
 * Copyright (c) 2008-2014 the original author or authors.
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

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;

/**
 * <p>The base class for HTTP transports that use blocking stream I/O.</p>
 */
public abstract class AbstractStreamHttpTransport extends AbstractHttpTransport
{
    private static final String SCHEDULER_ATTRIBUTE = "org.cometd.scheduler";

    protected AbstractStreamHttpTransport(BayeuxServerImpl bayeux, String name)
    {
        super(bayeux, name);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        getBayeux().setCurrentTransport(this);
        setCurrentRequest(request);
        try
        {
            process(request, response);
        }
        finally
        {
            setCurrentRequest(null);
            getBayeux().setCurrentTransport(null);
        }
    }

    protected void process(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        LongPollScheduler scheduler = (LongPollScheduler)request.getAttribute(SCHEDULER_ATTRIBUTE);
        if (scheduler == null)
        {
            // Not a resumed /meta/connect, process messages.
            try
            {
                ServerMessage.Mutable[] messages = parseMessages(request);
                processMessages(request, response, messages);
            }
            catch (ParseException x)
            {
                handleJSONParseException(request, response, x.getMessage(), x.getCause());
            }
        }
        else
        {
            resume(request, response, null, scheduler.getServerSession(), scheduler.getMetaConnectReply());
        }
    }

    @Override
    protected HttpScheduler suspend(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId, long timeout)
    {
        AsyncContext asyncContext = request.startAsync(request, response);
        asyncContext.setTimeout(0);
        HttpScheduler scheduler = newHttpScheduler(request, response, asyncContext, session, reply, browserId, timeout);
        request.setAttribute(SCHEDULER_ATTRIBUTE, scheduler);
        return scheduler;
    }

    protected HttpScheduler newHttpScheduler(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId, long timeout)
    {
        return new DispatchingLongPollScheduler(request, response, asyncContext, session, reply, browserId, timeout);
    }

    protected abstract ServerMessage.Mutable[] parseMessages(HttpServletRequest request) throws IOException, ParseException;

    protected ServerMessage.Mutable[] parseMessages(String[] requestParameters) throws IOException, ParseException
    {
        if (requestParameters == null || requestParameters.length == 0)
            throw new IOException("Missing '" + MESSAGE_PARAM + "' request parameter");

        if (requestParameters.length == 1)
            return parseMessages(requestParameters[0]);

        List<ServerMessage.Mutable> messages = new ArrayList<>();
        for (String batch : requestParameters)
        {
            if (batch == null)
                continue;
            messages.addAll(Arrays.asList(parseMessages(batch)));
        }
        return messages.toArray(new ServerMessage.Mutable[messages.size()]);
    }

    @Override
    @SuppressWarnings("ForLoopReplaceableByForEach")
    protected void write(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, boolean startInterval, List<ServerMessage> messages, ServerMessage.Mutable[] replies)
    {
        try
        {
            ServletOutputStream output;
            try
            {
                output = beginWrite(request, response);

                // Write the messages first.
                for (int i = 0; i < messages.size(); ++i)
                {
                    ServerMessage message = messages.get(i);
                    if (i > 0)
                        output.print(",");
                    writeMessage(response, session, message);
                }
            }
            finally
            {
                // Start the interval timeout after writing the messages
                // since they may take time to be written, even in case
                // of exceptions to make sure the session can be swept.
                if (startInterval && session != null && session.isConnected())
                    session.startIntervalTimeout(getInterval());
            }

            // Write the replies, if any.
            boolean needsComma = !messages.isEmpty();
            for (int i = 0; i < replies.length; ++i)
            {
                ServerMessage reply = replies[i];
                if (reply == null)
                    continue;
                if (needsComma)
                    output.print(",");
                needsComma = true;
                writeMessage(response, session, reply);
            }

            endWrite(response);
        }
        catch (Exception x)
        {
            AsyncContext asyncContext = null;
            if (request.isAsyncStarted())
                asyncContext = request.getAsyncContext();
            error(request, response, asyncContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected void writeMessage(HttpServletResponse response, ServerSessionImpl session, ServerMessage message) throws IOException
    {
        response.getOutputStream().write(message.getJSON().getBytes(response.getCharacterEncoding()));
    }

    protected abstract ServletOutputStream beginWrite(HttpServletRequest request, HttpServletResponse response) throws IOException;

    protected abstract void endWrite(HttpServletResponse response) throws IOException;

    protected class DispatchingLongPollScheduler extends LongPollScheduler
    {
        public DispatchingLongPollScheduler(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId, long timeout)
        {
            super(request, response, asyncContext, session, reply, browserId, timeout);
        }

        protected void dispatch()
        {
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
