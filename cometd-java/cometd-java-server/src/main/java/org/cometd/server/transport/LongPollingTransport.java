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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* ------------------------------------------------------------ */

/**
 * Abstract Long Polling Transport.
 * <p/>
 * Transports based on this class can be configured with servlet init parameters:
 * <dl>
 * <dt>browserId</dt><dd>The Cookie name used to save a browser ID.</dd>
 * <dt>maxSessionsPerBrowser</dt><dd>The maximum number of long polling sessions allowed per browser.</dd>
 * <dt>multiSessionInterval</dt><dd>The polling interval to use once max session per browser is exceeded.</dd>
 * <dt>autoBatch</dt><dd>If true a batch will be automatically created to span the handling of messages received from a session.</dd>
 * <dt>allowMultiSessionsNoBrowser</dt><dd>Allows multiple sessions even when the browser identifier cannot be retrieved.</dd>
 * </dl>
 */
public abstract class LongPollingTransport extends HttpTransport
{
    public final static String PREFIX = "long-polling";
    public final static String BROWSER_ID_OPTION = "browserId";
    public final static String BROWSER_COOKIE_NAME_OPTION = "browserCookieName";
    public final static String BROWSER_COOKIE_DOMAIN_OPTION = "browserCookieDomain";
    public final static String BROWSER_COOKIE_PATH_OPTION = "browserCookiePath";
    public final static String MAX_SESSIONS_PER_BROWSER_OPTION = "maxSessionsPerBrowser";
    public final static String MULTI_SESSION_INTERVAL_OPTION = "multiSessionInterval";
    public final static String AUTOBATCH_OPTION = "autoBatch";
    public final static String ALLOW_MULTI_SESSIONS_NO_BROWSER_OPTION = "allowMultiSessionsNoBrowser";

    private final Logger _logger = LoggerFactory.getLogger(getClass());
    private final ConcurrentHashMap<String, AtomicInteger> _browserMap = new ConcurrentHashMap<String, AtomicInteger>();
    private final Map<String, AtomicInteger> _browserSweep = new ConcurrentHashMap<String, AtomicInteger>();
    private String _browserCookieName;
    private String _browserCookieDomain;
    private String _browserCookiePath;
    private int _maxSessionsPerBrowser;
    private long _multiSessionInterval;
    private boolean _autoBatch;
    private boolean _allowMultiSessionsNoBrowser;
    private long _lastSweep;

    protected LongPollingTransport(BayeuxServerImpl bayeux, String name)
    {
        super(bayeux, name);
        setOptionPrefix(PREFIX);
    }

    @Override
    protected void init()
    {
        super.init();
        _browserCookieName = getOption(BROWSER_COOKIE_NAME_OPTION, getOption(BROWSER_ID_OPTION, "BAYEUX_BROWSER"));
        _browserCookieDomain = getOption(BROWSER_COOKIE_DOMAIN_OPTION, null);
        _browserCookiePath = getOption(BROWSER_COOKIE_PATH_OPTION, "/");
        _maxSessionsPerBrowser = getOption(MAX_SESSIONS_PER_BROWSER_OPTION, 1);
        _multiSessionInterval = getOption(MULTI_SESSION_INTERVAL_OPTION, 2000);
        _autoBatch = getOption(AUTOBATCH_OPTION, true);
        _allowMultiSessionsNoBrowser = getOption(ALLOW_MULTI_SESSIONS_NO_BROWSER_OPTION, false);
    }

    protected String findBrowserId(HttpServletRequest request)
    {
        Cookie[] cookies = request.getCookies();
        if (cookies != null)
        {
            for (Cookie cookie : cookies)
            {
                if (_browserCookieName.equals(cookie.getName()))
                    return cookie.getValue();
            }
        }
        return null;
    }

    protected String setBrowserId(HttpServletRequest request, HttpServletResponse response)
    {
        String browserId = Long.toHexString(request.getRemotePort()) +
                Long.toString(getBayeux().randomLong(), 36) +
                Long.toString(System.currentTimeMillis(), 36) +
                Long.toString(request.getRemotePort(), 36);
        Cookie cookie = new Cookie(_browserCookieName, browserId);
        if (_browserCookieDomain != null)
            cookie.setDomain(_browserCookieDomain);
        cookie.setPath(_browserCookiePath);
        cookie.setMaxAge(-1);
        response.addCookie(cookie);
        return browserId;
    }

    /**
     * Increment the browser ID count.
     *
     * @param browserId the browser ID to increment the count for
     * @return true if the browser ID count is below the max sessions per browser value.
     * If false is returned, the count is not incremented.
     */
    protected boolean incBrowserId(String browserId)
    {
        if (_maxSessionsPerBrowser < 0)
            return true;
        if (_maxSessionsPerBrowser == 0)
            return false;

        AtomicInteger count = _browserMap.get(browserId);
        if (count == null)
        {
            AtomicInteger newCount = new AtomicInteger();
            count = _browserMap.putIfAbsent(browserId, newCount);
            if (count == null)
                count = newCount;
        }

        // Increment
        int sessions = count.incrementAndGet();

        // If was zero, remove from the sweep
        if (sessions == 1)
            _browserSweep.remove(browserId);

        // TODO, the maxSessionsPerBrowser should be parameterized on user-agent
        if (sessions > _maxSessionsPerBrowser)
        {
            count.decrementAndGet();
            return false;
        }

        return true;
    }

    protected void decBrowserId(String browserId)
    {
        if (browserId == null)
            return;

        AtomicInteger count = _browserMap.get(browserId);
        if (count != null && count.decrementAndGet() == 0)
        {
            _browserSweep.put(browserId, new AtomicInteger(0));
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        LongPollScheduler scheduler = (LongPollScheduler)request.getAttribute(LongPollScheduler.ATTRIBUTE);
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
            resume(request, response, scheduler.getSession(), scheduler.getReply());
        }
    }

    protected void processMessages(HttpServletRequest request, HttpServletResponse response, ServerMessage.Mutable[] messages) throws IOException
    {
        boolean autoBatch = _autoBatch;
        ServerSessionImpl session = null;
        boolean batch = false;
        boolean sendQueue = true;
        boolean sendReplies = true;
        boolean startInterval = false;
        boolean disconnected = false;
        try
        {
            for (int i = 0; i < messages.length; ++i)
            {
                ServerMessage.Mutable message = messages[i];
                _logger.debug("Processing {}", message);

                if (session == null && !disconnected)
                    session = (ServerSessionImpl)getBayeux().getSession(message.getClientId());

                if (session != null)
                {
                    disconnected = !session.isHandshook();
                    if (disconnected)
                    {
                        if (batch)
                        {
                            batch = false;
                            session.endBatch();
                        }
                        session = null;
                    }
                    else
                    {
                        if (autoBatch && !batch)
                        {
                            batch = true;
                            session.startBatch();
                        }
                    }
                }

                String channelName = message.getChannel();
                if (channelName.equals(Channel.META_HANDSHAKE))
                {
                    if (messages.length > 1)
                        throw new IOException();
                    ServerMessage.Mutable reply = processMetaHandshake(request, response, session, message);
                    if (reply != null)
                        session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());
                    messages[i] = processReply(session, reply);
                    sendQueue = false;
                }
                else if (channelName.equals(Channel.META_CONNECT))
                {
                    if (messages.length > 1)
                        throw new IOException();
                    ServerMessage.Mutable reply = processMetaConnect(request, response, session, message);
                    messages[i] = processReply(session, reply);
                    startInterval = sendQueue = sendReplies = reply != null;
                }
                else
                {
                    ServerMessage.Mutable reply = bayeuxServerHandle(session, message);
                    messages[i] = processReply(session, reply);
                }
            }

            if (sendReplies || sendQueue)
                flush(request, response, session, sendQueue, startInterval, messages);
        }
        finally
        {
            if (batch)
                session.endBatch();
        }
    }

    protected ServerMessage.Mutable processMetaHandshake(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable message)
    {
        ServerMessage.Mutable reply = bayeuxServerHandle(session, message);
        if (reply != null)
        {
            session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());
            if (session != null)
            {
                String browserId = findBrowserId(request);
                if (browserId == null)
                    setBrowserId(request, response);
            }
        }
        return reply;
    }

    protected ServerMessage.Mutable processMetaConnect(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable message)
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
                String browserId = findBrowserId(request);
                boolean allowSuspendConnect;
                if (browserId != null)
                    allowSuspendConnect = incBrowserId(browserId);
                else
                    allowSuspendConnect = _allowMultiSessionsNoBrowser || request.getHeader("Origin") != null;

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

                        Continuation continuation = ContinuationSupport.getContinuation(request);
                        continuation.setTimeout(timeout);
                        continuation.suspend(response);
                        LongPollScheduler scheduler = newLongPollScheduler(session, continuation, reply, browserId);
                        request.setAttribute(LongPollScheduler.ATTRIBUTE, scheduler);
                        metaConnectSuspended(request, session, timeout);
                        // Setting the scheduler may resume the /meta/connect
                        session.setScheduler(scheduler);
                        reply = null;
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

                    long multiSessionInterval = _multiSessionInterval;
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

        return reply;
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

    protected void resume(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable reply)
    {
        metaConnectResumed(request, session);
        Map<String, Object> advice = session.takeAdvice();
        if (advice != null)
            reply.put(Message.ADVICE_FIELD, advice);
        if (session.isDisconnected())
            reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);

        flush(request, response, session, true, true, processReply(session, reply));
    }

    protected void flush(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, boolean sendQueue, boolean startInterval, ServerMessage.Mutable... replies)
    {
        List<ServerMessage> messages = Collections.emptyList();
        if (session != null)
        {
            boolean metaConnectDelivery = isMetaConnectDeliveryOnly() || session.isMetaConnectDeliveryOnly();
            if (sendQueue && (startInterval || !metaConnectDelivery))
                messages = session.takeQueue();
        }
        write(request, response, session, startInterval, messages, replies);
    }

    protected void write(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, boolean startInterval, List<ServerMessage> messages, ServerMessage.Mutable[] replies)
    {
        try
        {
            ServletOutputStream output;
            try
            {
                output = beginWrite(request, response, session);

                // Write the messages first.
                for (int i = 0; i < messages.size(); ++i)
                {
                    ServerMessage message = messages.get(i);
                    if (i > 0)
                        output.write(',');
                    writeMessage(output, session, message);
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
                    output.write(',');
                needsComma = true;
                writeMessage(output, session, reply);
            }

            endWrite(output, session);
        }
        catch (Exception x)
        {
            try
            {
                if (!response.isCommitted())
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            catch (Exception xx)
            {
                _logger.trace("Could not send " + HttpServletResponse.SC_INTERNAL_SERVER_ERROR + " response", xx);
            }
        }
    }

    protected void writeMessage(ServletOutputStream output, ServerSessionImpl session, ServerMessage message) throws IOException
    {
        output.write(message.getJSON().getBytes("UTF-8"));
    }

    protected abstract ServletOutputStream beginWrite(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session) throws IOException;

    protected abstract void endWrite(ServletOutputStream output, ServerSessionImpl session) throws IOException;

    protected LongPollScheduler newLongPollScheduler(ServerSessionImpl session, Continuation continuation, ServerMessage.Mutable metaConnectReply, String browserId)
    {
        return new LongPollScheduler(session, continuation, metaConnectReply, browserId);
    }

    protected ServerMessage.Mutable bayeuxServerHandle(ServerSessionImpl session, ServerMessage.Mutable message)
    {
        return getBayeux().handle(session, message);
    }

    protected void metaConnectSuspended(HttpServletRequest request, ServerSession session, long timeout)
    {
    }

    protected void metaConnectResumed(HttpServletRequest request, ServerSession session)
    {
    }

    protected void handleJSONParseException(HttpServletRequest request, HttpServletResponse response, String json, Throwable exception) throws ServletException, IOException
    {
        _logger.warn("Error parsing JSON: " + json, exception);
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
    }

    /**
     * Sweep the transport for old Browser IDs
     *
     * @see org.cometd.server.AbstractServerTransport#sweep()
     */
    protected void sweep()
    {
        long now = System.currentTimeMillis();
        long elapsed = now - _lastSweep;
        if (_lastSweep > 0 && elapsed > 0)
        {
            // Calculate the maximum sweeps that a browser ID can be 0 as the
            // maximum interval time divided by the sweep period, doubled for safety
            int maxSweeps = (int)(2 * getMaxInterval() / elapsed);

            for (Map.Entry<String, AtomicInteger> entry : _browserSweep.entrySet())
            {
                AtomicInteger count = entry.getValue();
                // if the ID has been in the sweep map for 3 sweeps
                if (count != null && count.incrementAndGet() > maxSweeps)
                {
                    String key = entry.getKey();
                    // remove it from both browser Maps
                    if (_browserSweep.remove(key) == count && _browserMap.get(key).get() == 0)
                    {
                        _browserMap.remove(key);
                        _logger.debug("Swept browserId {}", key);
                    }
                }
            }
        }
        _lastSweep = now;
    }

    protected ServerMessage.Mutable[] parseMessages(String[] requestParameters) throws IOException, ParseException
    {
        if (requestParameters == null || requestParameters.length == 0)
            throw new IOException("Missing '" + MESSAGE_PARAM + "' request parameter");

        if (requestParameters.length == 1)
            return parseMessages(requestParameters[0]);

        List<ServerMessage.Mutable> messages = new ArrayList<ServerMessage.Mutable>();
        for (String batch : requestParameters)
        {
            if (batch == null)
                continue;
            messages.addAll(Arrays.asList(parseMessages(batch)));
        }
        return messages.toArray(new ServerMessage.Mutable[messages.size()]);
    }

    protected abstract ServerMessage.Mutable[] parseMessages(HttpServletRequest request) throws IOException, ParseException;

    /**
     * @return true if the transport always flushes at the end of a call to {@link #handle(HttpServletRequest, HttpServletResponse)}.
     */
    protected abstract boolean isAlwaysFlushingAfterHandle();

    protected class LongPollScheduler implements AbstractServerTransport.OneTimeScheduler, ContinuationListener
    {
        private static final String ATTRIBUTE = "org.cometd.scheduler";

        private final ServerSessionImpl _session;
        private final Continuation _continuation;
        private final ServerMessage.Mutable _reply;
        private final String _browserId;

        public LongPollScheduler(ServerSessionImpl session, Continuation continuation, ServerMessage.Mutable reply, String browserId)
        {
            _session = session;
            _continuation = continuation;
            _continuation.addContinuationListener(this);
            _reply = reply;
            _browserId = browserId;
        }

        public void cancel()
        {
            if (_continuation != null && _continuation.isSuspended() && !_continuation.isExpired())
            {
                try
                {
                    decBrowserId();
                    ((HttpServletResponse)_continuation.getServletResponse()).sendError(HttpServletResponse.SC_REQUEST_TIMEOUT);
                }
                catch (IOException x)
                {
                    _logger.trace("", x);
                }

                try
                {
                    _continuation.complete();
                }
                catch (Exception x)
                {
                    _logger.trace("", x);
                }
            }
        }

        public void schedule()
        {
            decBrowserId();
            _continuation.resume();
        }

        public ServerSessionImpl getSession()
        {
            return _session;
        }

        public ServerMessage.Mutable getReply()
        {
            Map<String, Object> advice = _session.takeAdvice();
            if (advice != null)
                _reply.put(Message.ADVICE_FIELD, advice);
            return _reply;
        }

        public void onComplete(Continuation continuation)
        {
        }

        public void onTimeout(Continuation continuation)
        {
            decBrowserId();
            _session.setScheduler(null);
        }

        private void decBrowserId()
        {
            LongPollingTransport.this.decBrowserId(_browserId);
        }
    }
}
