/*
 * Copyright (c) 2010 the original author or authors.
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
import java.io.PrintWriter;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
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
    public final static String MAX_SESSIONS_PER_BROWSER_OPTION = "maxSessionsPerBrowser";
    public final static String MULTI_SESSION_INTERVAL_OPTION = "multiSessionInterval";
    public final static String AUTOBATCH_OPTION = "autoBatch";
    public final static String ALLOW_MULTI_SESSIONS_NO_BROWSER_OPTION = "allowMultiSessionsNoBrowser";

    private final Logger _logger = LoggerFactory.getLogger(getClass());
    private final ConcurrentHashMap<String, AtomicInteger> _browserMap = new ConcurrentHashMap<String, AtomicInteger>();
    private final Map<String, AtomicInteger> _browserSweep = new ConcurrentHashMap<String, AtomicInteger>();
    private String _browserId = "BAYEUX_BROWSER";
    private int _maxSessionsPerBrowser = 1;
    private long _multiSessionInterval = 2000;
    private boolean _autoBatch = true;
    private boolean _allowMultiSessionsNoBrowser = false;
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
        _browserId = getOption(BROWSER_ID_OPTION, _browserId);
        _maxSessionsPerBrowser = getOption(MAX_SESSIONS_PER_BROWSER_OPTION, _maxSessionsPerBrowser);
        _multiSessionInterval = getOption(MULTI_SESSION_INTERVAL_OPTION, _multiSessionInterval);
        _autoBatch = getOption(AUTOBATCH_OPTION, _autoBatch);
        _allowMultiSessionsNoBrowser = getOption(ALLOW_MULTI_SESSIONS_NO_BROWSER_OPTION, _allowMultiSessionsNoBrowser);
    }

    protected String findBrowserId(HttpServletRequest request)
    {
        Cookie[] cookies = request.getCookies();
        if (cookies != null)
        {
            for (Cookie cookie : cookies)
            {
                if (_browserId.equals(cookie.getName()))
                    return cookie.getValue();
            }
        }
        return null;
    }

    protected String setBrowserId(HttpServletRequest request, HttpServletResponse response)
    {
        String browser_id = Long.toHexString(request.getRemotePort()) +
                Long.toString(getBayeux().randomLong(), 36) +
                Long.toString(System.currentTimeMillis(), 36) +
                Long.toString(request.getRemotePort(), 36);
        Cookie cookie = new Cookie(_browserId, browser_id);
        cookie.setPath("/");
        cookie.setMaxAge(-1);
        response.addCookie(cookie);
        return browser_id;
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
            AtomicInteger new_count = new AtomicInteger();
            count = _browserMap.putIfAbsent(browserId, new_count);
            if (count == null)
                count = new_count;
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
        // Is this a resumed connect?
        LongPollScheduler scheduler = (LongPollScheduler)request.getAttribute(LongPollScheduler.ATTRIBUTE);
        if (scheduler == null)
        {
            // No - process messages

            // Remember if we start a batch
            boolean batch = false;

            // Don't know the session until first message or handshake response.
            ServerSessionImpl session = null;
            boolean connect = false;

            try
            {
                ServerMessage.Mutable[] messages = parseMessages(request);
                if (messages == null)
                    return;

                PrintWriter writer = null;
                for (ServerMessage.Mutable message : messages)
                {
                    // Is this a connect?
                    connect = Channel.META_CONNECT.equals(message.getChannel());

                    // Get the session from the message
                    String client_id = message.getClientId();
                    if (session == null || client_id != null && !client_id.equals(session.getId()))
                    {
                        session = (ServerSessionImpl)getBayeux().getSession(client_id);
                        if (_autoBatch && !batch && session != null && !connect && !message.isMeta())
                        {
                            // start a batch to group all resulting messages into a single response.
                            batch = true;
                            session.startBatch();
                        }
                    }
                    else if (!session.isHandshook())
                    {
                        batch = false;
                        session = null;
                    }

                    if (connect && session != null)
                    {
                        // cancel previous scheduler to cancel any prior waiting long poll
                        // this should also dec the browser ID
                        session.setScheduler(null);
                    }

                    boolean wasConnected = session != null && session.isConnected();

                    // Forward handling of the message.
                    // The actual reply is return from the call, but other messages may
                    // also be queued on the session.
                    ServerMessage.Mutable reply = bayeuxServerHandle(session, message);

                    // Do we have a reply ?
                    if (reply != null)
                    {
                        if (session == null)
                        {
                            // This must be a handshake, extract a session from the reply
                            session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());

                            // Get the user agent while we are at it, and add the browser ID cookie
                            if (session != null)
                            {
                                String userAgent = request.getHeader("User-Agent");
                                session.setUserAgent(userAgent);

                                String browserId = findBrowserId(request);
                                if (browserId == null)
                                    setBrowserId(request, response);
                            }
                        }
                        else
                        {
                            // Special handling for connect
                            if (connect)
                            {
                                try
                                {
                                    writer = sendQueue(request, response, session, writer);

                                    // If the writer is non null, we have already started sending a response, so we should not suspend
                                    if (writer == null && reply.isSuccessful() && session.isQueueEmpty())
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
                                                // Suspend and wait for messages
                                                Continuation continuation = ContinuationSupport.getContinuation(request);
                                                continuation.setTimeout(timeout);
                                                continuation.suspend(response);
                                                scheduler = new LongPollScheduler(session, continuation, reply, browserId);
                                                session.setScheduler(scheduler);
                                                request.setAttribute(LongPollScheduler.ATTRIBUTE, scheduler);
                                                reply = null;
                                                metaConnectSuspended(request, session, timeout);
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

                                            if (_multiSessionInterval > 0)
                                            {
                                                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
                                                advice.put(Message.INTERVAL_FIELD, _multiSessionInterval);
                                            }
                                            else
                                            {
                                                advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                                                reply.setSuccessful(false);
                                            }
                                            session.reAdvise();
                                        }
                                    }
                                }
                                finally
                                {
                                    if (reply != null && session.isConnected())
                                        session.startIntervalTimeout(getInterval());
                                }
                            }
                            else
                            {
                                if (!isMetaConnectDeliveryOnly() && !session.isMetaConnectDeliveryOnly())
                                {
                                    writer = sendQueue(request, response, session, writer);
                                }
                            }
                        }

                        // If the reply has not been otherwise handled, send it
                        if (reply != null)
                        {
                            if (connect && session != null && !session.isConnected())
                                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);

                            reply = getBayeux().extendReply(session, session, reply);

                            if (reply != null)
                            {
                                getBayeux().freeze(reply);
                                writer = send(request, response, writer, reply);
                            }
                        }
                    }

                    // Disassociate the reply
                    message.setAssociated(null);
                }
                if (writer != null)
                    complete(writer);
            }
            catch (ParseException x)
            {
                handleJSONParseException(request, response, x.getMessage(), x.getCause());
            }
            finally
            {
                // If we started a batch, end it now
                if (batch)
                {
                    boolean ended = session.endBatch();

                    // Flush session if not done by the batch, since some browser order <script> requests
                    if (!ended && isAlwaysFlushingAfterHandle())
                        session.flush();
                }
                else if (session != null && !connect && isAlwaysFlushingAfterHandle())
                {
                    session.flush();
                }
            }
        }
        else
        {
            // Get the resumed session
            ServerSessionImpl session = scheduler.getSession();
            metaConnectResumed(request, session);

            PrintWriter writer;
            try
            {
                // Send the message queue
                writer = sendQueue(request, response, session, null);
            }
            finally
            {
                // We need to start the interval timeout before the connect reply
                // otherwise we open up a race condition where the client receives
                // the connect reply and sends a new connect request before we start
                // the interval timeout, which will be wrong.
                // We need to put this into a finally block in case sending the queue
                // throws an exception (for example because the client is gone), so that
                // we start the interval timeout that is important to sweep the session
                if (session.isConnected())
                    session.startIntervalTimeout(getInterval());
            }

            // Send the connect reply
            ServerMessage.Mutable reply = scheduler.getReply();

            if (!session.isConnected())
                reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);

            reply = getBayeux().extendReply(session, session, reply);

            if (reply != null)
            {
                getBayeux().freeze(reply);
                writer = send(request, response, writer, reply);
            }

            complete(writer);
        }
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
                if (count!=null && count.incrementAndGet() > maxSweeps)
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

    private PrintWriter sendQueue(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, PrintWriter writer)
            throws IOException
    {
        final List<ServerMessage> queue = session.takeQueue();
        for (ServerMessage m : queue)
            writer = send(request, response, writer, m);
        return writer;
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

    protected abstract PrintWriter send(HttpServletRequest request, HttpServletResponse response, PrintWriter writer, ServerMessage message) throws IOException;

    protected abstract void complete(PrintWriter writer) throws IOException;

    private class LongPollScheduler implements AbstractServerTransport.OneTimeScheduler, ContinuationListener
    {
        private static final String ATTRIBUTE = "org.cometd.scheduler";

        private final ServerSessionImpl _session;
        private final Continuation _continuation;
        private final ServerMessage.Mutable _reply;
        private String _browserId;

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
            decBrowserId();
        }

        public void onTimeout(Continuation continuation)
        {
            _session.setScheduler(null);
        }

        private void decBrowserId()
        {
            LongPollingTransport.this.decBrowserId(_browserId);
            _browserId = null;
        }
    }
}
