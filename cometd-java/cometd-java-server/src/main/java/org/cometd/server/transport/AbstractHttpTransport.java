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
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>HTTP ServerTransport base class, used by ServerTransports that use
 * HTTP as transport or to initiate a transport connection.</p>
 */
public abstract class AbstractHttpTransport extends AbstractServerTransport
{
    public final static String PREFIX = "long-polling";
    public static final String JSON_DEBUG_OPTION = "jsonDebug";
    public static final String MESSAGE_PARAM = "message";
    public final static String BROWSER_COOKIE_NAME_OPTION = "browserCookieName";
    public final static String BROWSER_COOKIE_DOMAIN_OPTION = "browserCookieDomain";
    public final static String BROWSER_COOKIE_PATH_OPTION = "browserCookiePath";
    public final static String BROWSER_COOKIE_SECURE_OPTION = "browserCookieSecure";
    public final static String BROWSER_COOKIE_HTTP_ONLY_OPTION = "browserCookieHttpOnly";
    public final static String MAX_SESSIONS_PER_BROWSER_OPTION = "maxSessionsPerBrowser";
    public final static String MULTI_SESSION_INTERVAL_OPTION = "multiSessionInterval";
    public final static String AUTOBATCH_OPTION = "autoBatch";
    public final static String ALLOW_MULTI_SESSIONS_NO_BROWSER_OPTION = "allowMultiSessionsNoBrowser";

    protected final Logger _logger = LoggerFactory.getLogger(getClass());
    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<>();
    private final Map<String, Collection<ServerSessionImpl>> _sessions = new HashMap<>();
    private final ConcurrentMap<String, AtomicInteger> _browserMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> _browserSweep = new ConcurrentHashMap<>();
    private String _browserCookieName;
    private String _browserCookieDomain;
    private String _browserCookiePath;
    private boolean _browserCookieSecure;
    private boolean _browserCookieHttpOnly;
    private int _maxSessionsPerBrowser;
    private long _multiSessionInterval;
    private boolean _autoBatch;
    private boolean _allowMultiSessionsNoBrowser;
    private long _lastSweep;

    protected AbstractHttpTransport(BayeuxServerImpl bayeux, String name)
    {
        super(bayeux, name);
        setOptionPrefix(PREFIX);
    }

    @Override
    public void init()
    {
        super.init();
        _browserCookieName = getOption(BROWSER_COOKIE_NAME_OPTION, "BAYEUX_BROWSER");
        _browserCookieDomain = getOption(BROWSER_COOKIE_DOMAIN_OPTION, null);
        _browserCookiePath = getOption(BROWSER_COOKIE_PATH_OPTION, "/");
        _browserCookieSecure = getOption(BROWSER_COOKIE_SECURE_OPTION, false);
        _browserCookieHttpOnly = getOption(BROWSER_COOKIE_HTTP_ONLY_OPTION, true);
        _maxSessionsPerBrowser = getOption(MAX_SESSIONS_PER_BROWSER_OPTION, 1);
        _multiSessionInterval = getOption(MULTI_SESSION_INTERVAL_OPTION, 2000);
        _autoBatch = getOption(AUTOBATCH_OPTION, true);
        _allowMultiSessionsNoBrowser = getOption(ALLOW_MULTI_SESSIONS_NO_BROWSER_OPTION, false);
    }

    protected long getMultiSessionInterval()
    {
        return _multiSessionInterval;
    }

    protected boolean isAutoBatch()
    {
        return _autoBatch;
    }

    protected boolean isAllowMultiSessionsNoBrowser()
    {
        return _allowMultiSessionsNoBrowser;
    }

    public void setCurrentRequest(HttpServletRequest request)
    {
        _currentRequest.set(request);
    }

    public HttpServletRequest getCurrentRequest()
    {
        return _currentRequest.get();
    }

    public abstract boolean accept(HttpServletRequest request);

    public abstract void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;

    protected abstract HttpScheduler suspend(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId, long timeout);

    protected abstract void write(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, boolean startInterval, List<ServerMessage> messages, ServerMessage.Mutable[] replies);

    protected void processMessages(HttpServletRequest request, HttpServletResponse response, ServerMessage.Mutable[] messages) throws IOException
    {
        Collection<ServerSessionImpl> sessions = findCurrentSessions(request);
        ServerSessionImpl session = null;
        boolean autoBatch = isAutoBatch();
        boolean batch = false;
        boolean sendQueue = true;
        boolean sendReplies = true;
        boolean startInterval = false;
        try
        {
            for (int i = 0; i < messages.length; ++i)
            {
                ServerMessage.Mutable message = messages[i];
                if (_logger.isDebugEnabled())
                    _logger.debug("Processing {}", message);

                // Try to find the session.
                if (sessions != null)
                {
                    String clientId = message.getClientId();
                    if (clientId != null)
                    {
                        for (ServerSessionImpl s : sessions)
                        {
                            if (s.getId().equals(clientId))
                            {
                                session = s;
                                break;
                            }
                        }
                    }
                }

                if (session != null)
                {
                    if (session.isHandshook())
                    {
                        if (autoBatch && !batch)
                        {
                            batch = true;
                            session.startBatch();
                        }
                    }
                    else
                    {
                        // Disconnected concurrently.
                        if (batch)
                        {
                            batch = false;
                            session.endBatch();
                        }
                        session = null;
                    }
                }

                switch (message.getChannel())
                {
                    case Channel.META_HANDSHAKE:
                    {
                        if (messages.length > 1)
                            throw new IOException();
                        ServerMessage.Mutable reply = processMetaHandshake(request, response, session, message);
                        if (reply != null)
                            session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());
                        messages[i] = processReply(session, reply);
                        startInterval = sendQueue = isAllowMessageDeliveryDuringHandshake() && reply != null && reply.isSuccessful();
                        break;
                    }
                    case Channel.META_CONNECT:
                    {
                        ServerMessage.Mutable reply = processMetaConnect(request, response, session, message);
                        messages[i] = processReply(session, reply);
                        startInterval = sendQueue = sendReplies = reply != null;
                        break;
                    }
                    default:
                    {
                        ServerMessage.Mutable reply = bayeuxServerHandle(session, message);
                        messages[i] = processReply(session, reply);
                        break;
                    }
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

    protected Collection<ServerSessionImpl> findCurrentSessions(HttpServletRequest request)
    {
        Cookie[] cookies = request.getCookies();
        if (cookies != null)
        {
            for (Cookie cookie : cookies)
            {
                if (_browserCookieName.equals(cookie.getName()))
                {
                    synchronized (_sessions)
                    {
                        return _sessions.get(cookie.getValue());
                    }
                }
            }
        }
        return null;
    }

    protected ServerMessage.Mutable processMetaHandshake(HttpServletRequest request, HttpServletResponse response, ServerSessionImpl session, ServerMessage.Mutable message)
    {
        ServerMessage.Mutable reply = bayeuxServerHandle(session, message);
        if (reply != null)
        {
            session = (ServerSessionImpl)getBayeux().getSession(reply.getClientId());
            if (session != null)
            {
                String id = findBrowserId(request);
                if (id == null)
                    id = setBrowserId(request, response);
                final String browserId = id;

                synchronized (_sessions)
                {
                    Collection<ServerSessionImpl> sessions = _sessions.get(browserId);
                    if (sessions == null)
                    {
                        // The list is modified inside sync blocks, but
                        // iterated outside, so it must be concurrent.
                        sessions = new CopyOnWriteArrayList<>();
                        _sessions.put(browserId, sessions);
                    }
                    sessions.add(session);
                }

                session.addListener(new ServerSession.RemoveListener()
                {
                    @Override
                    public void removed(ServerSession session, boolean timeout)
                    {
                        synchronized (_sessions)
                        {
                            Collection<ServerSessionImpl> sessions = _sessions.get(browserId);
                            sessions.remove(session);
                            if (sessions.isEmpty())
                                _sessions.remove(browserId);
                        }
                    }
                });
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
                    allowSuspendConnect = incBrowserId(browserId, session);
                else
                    allowSuspendConnect = isAllowMultiSessionsNoBrowser() || request.getHeader("Origin") != null;

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

                        HttpScheduler scheduler = suspend(request, response, session, reply, browserId, timeout);
                        metaConnectSuspended(request, response, scheduler.getAsyncContext(), session);
                        // Setting the scheduler may resume the /meta/connect
                        session.setScheduler(scheduler);
                        reply = null;
                    }
                    else
                    {
                        decBrowserId(browserId, session);
                    }
                }
                else
                {
                    // There are multiple sessions from the same browser
                    Map<String, Object> advice = reply.getAdvice(true);

                    if (browserId != null)
                        advice.put("multiple-clients", true);

                    long multiSessionInterval = getMultiSessionInterval();
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

    protected void resume(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply)
    {
        metaConnectResumed(request, response, asyncContext, session);
        Map<String, Object> advice = session.takeAdvice(this);
        if (advice != null)
            reply.put(Message.ADVICE_FIELD, advice);
        if (session.isDisconnected())
            reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);

        flush(request, response, session, true, true, processReply(session, reply));
    }

    public BayeuxContext getContext()
    {
        HttpServletRequest request = getCurrentRequest();
        if (request != null)
            return new HttpContext(request);
        return null;
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
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 16)
            builder.append(Long.toString(getBayeux().randomLong(), 36));
        builder.setLength(16);
        String browserId = builder.toString();
        Cookie cookie = new Cookie(_browserCookieName, browserId);
        if (_browserCookieDomain != null)
            cookie.setDomain(_browserCookieDomain);
        cookie.setPath(_browserCookiePath);
        cookie.setSecure(_browserCookieSecure);
        cookie.setHttpOnly(_browserCookieHttpOnly);
        cookie.setMaxAge(-1);
        response.addCookie(cookie);
        return browserId;
    }

    /**
     * Increment the browser ID count.
     *
     *
     * @param browserId the browser ID to increment the count for
     * @param session the session that cause the browser ID increment
     * @return true if the browser ID count is below the max sessions per browser value.
     * If false is returned, the count is not incremented.
     */
    protected boolean incBrowserId(String browserId, ServerSession session)
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

        boolean result = true;
        if (sessions > _maxSessionsPerBrowser)
        {
            sessions = count.decrementAndGet();
            result = false;
        }

        if (_logger.isDebugEnabled())
            _logger.debug("> client {} {} sessions from {}", browserId, sessions, session);

        return result;
    }

    protected void decBrowserId(String browserId, ServerSession session)
    {
        if (browserId == null)
            return;

        int sessions = -1;
        AtomicInteger count = _browserMap.get(browserId);
        if (count != null)
            sessions = count.decrementAndGet();

        if (sessions == 0)
            _browserSweep.put(browserId, new AtomicInteger(0));

        if (_logger.isDebugEnabled())
            _logger.debug("< client {} {} sessions for {}", browserId, sessions, session);
    }

    protected void handleJSONParseException(HttpServletRequest request, HttpServletResponse response, String json, Throwable exception) throws IOException
    {
        _logger.warn("Could not parse JSON: " + json, exception);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    protected void error(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, int responseCode)
    {
        try
        {
            response.setStatus(responseCode);
        }
        catch (Exception x)
        {
            _logger.trace("Could not send " + responseCode + " response", x);
        }
        finally
        {
            try
            {
                if (asyncContext != null)
                    asyncContext.complete();
            }
            catch (Exception x)
            {
                _logger.trace("Could not complete " + responseCode + " response", x);
            }
        }
    }

    protected ServerMessage.Mutable bayeuxServerHandle(ServerSessionImpl session, ServerMessage.Mutable message)
    {
        return getBayeux().handle(session, message);
    }

    protected void metaConnectSuspended(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSession session)
    {
        if (_logger.isDebugEnabled())
            _logger.debug("Suspended request {}", request);
    }

    protected void metaConnectResumed(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSession session)
    {
        if (_logger.isDebugEnabled())
            _logger.debug("Resumed request {}", request);
    }

    /**
     * Sweeps the transport for old Browser IDs
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
                        if (_logger.isDebugEnabled())
                            _logger.debug("Swept browserId {}", key);
                    }
                }
            }
        }
        _lastSweep = now;
    }

    private static class HttpContext implements BayeuxContext
    {
        final HttpServletRequest _request;

        HttpContext(HttpServletRequest request)
        {
            _request = request;
        }

        public Principal getUserPrincipal()
        {
            return _request.getUserPrincipal();
        }

        public boolean isUserInRole(String role)
        {
            return _request.isUserInRole(role);
        }

        public InetSocketAddress getRemoteAddress()
        {
            return new InetSocketAddress(_request.getRemoteHost(), _request.getRemotePort());
        }

        public InetSocketAddress getLocalAddress()
        {
            return new InetSocketAddress(_request.getLocalName(), _request.getLocalPort());
        }

        public String getHeader(String name)
        {
            return _request.getHeader(name);
        }

        public List<String> getHeaderValues(String name)
        {
            return Collections.list(_request.getHeaders(name));
        }

        public String getParameter(String name)
        {
            return _request.getParameter(name);
        }

        public List<String> getParameterValues(String name)
        {
            return Arrays.asList(_request.getParameterValues(name));
        }

        public String getCookie(String name)
        {
            Cookie[] cookies = _request.getCookies();
            if (cookies != null)
            {
                for (Cookie c : cookies)
                {
                    if (name.equals(c.getName()))
                        return c.getValue();
                }
            }
            return null;
        }

        public String getHttpSessionId()
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                return session.getId();
            return null;
        }

        public Object getHttpSessionAttribute(String name)
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                return session.getAttribute(name);
            return null;
        }

        public void setHttpSessionAttribute(String name, Object value)
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                session.setAttribute(name, value);
            else
                throw new IllegalStateException("!session");
        }

        public void invalidateHttpSession()
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                session.invalidate();
        }

        public Object getRequestAttribute(String name)
        {
            return _request.getAttribute(name);
        }

        private ServletContext getServletContext()
        {
            HttpSession s = _request.getSession(false);
            if (s != null)
            {
                return s.getServletContext();
            }
            else
            {
                s = _request.getSession(true);
                ServletContext servletContext = s.getServletContext();
                s.invalidate();
                return servletContext;
            }
        }

        public Object getContextAttribute(String name)
        {
            return getServletContext().getAttribute(name);
        }

        public String getContextInitParameter(String name)
        {
            return getServletContext().getInitParameter(name);
        }

        public String getURL()
        {
            StringBuffer url = _request.getRequestURL();
            String query = _request.getQueryString();
            if (query != null)
                url.append("?").append(query);
            return url.toString();
        }

        @Override
        public List<Locale> getLocales()
        {
            return Collections.list(_request.getLocales());
        }
    }

    public interface HttpScheduler extends Scheduler
    {
        public HttpServletRequest getRequest();

        public HttpServletResponse getResponse();

        public AsyncContext getAsyncContext();
    }

    protected abstract class LongPollScheduler implements Runnable, HttpScheduler, AsyncListener
    {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final AsyncContext asyncContext;
        private final ServerSessionImpl session;
        private final ServerMessage.Mutable reply;
        private final String browserId;
        private final org.eclipse.jetty.util.thread.Scheduler.Task task;
        private final AtomicBoolean cancel;

        protected LongPollScheduler(HttpServletRequest request, HttpServletResponse response, AsyncContext asyncContext, ServerSessionImpl session, ServerMessage.Mutable reply, String browserId, long timeout)
        {
            this.request = request;
            this.response = response;
            this.asyncContext = asyncContext;
            this.session = session;
            this.reply = reply;
            this.browserId = browserId;
            asyncContext.addListener(this);
            this.task = getBayeux().schedule(this, timeout);
            this.cancel = new AtomicBoolean();
        }

        @Override
        public HttpServletRequest getRequest()
        {
            return request;
        }

        @Override
        public HttpServletResponse getResponse()
        {
            return response;
        }

        @Override
        public AsyncContext getAsyncContext()
        {
            return asyncContext;
        }

        public ServerSessionImpl getServerSession()
        {
            return session;
        }

        public ServerMessage.Mutable getMetaConnectReply()
        {
            return reply;
        }

        @Override
        public void schedule()
        {
            if (cancelTimeout())
            {
                if (_logger.isDebugEnabled())
                    _logger.debug("Resuming /meta/connect after schedule");
                resume();
            }
        }

        @Override
        public void cancel()
        {
            if (cancelTimeout())
            {
                if (_logger.isDebugEnabled())
                    _logger.debug("Duplicate /meta/connect, cancelling {}", reply);
                error(HttpServletResponse.SC_REQUEST_TIMEOUT);
            }
        }

        private boolean cancelTimeout()
        {
            // Cannot rely on the return value of task.cancel()
            // since it may be invoked when the task is in run()
            // where cancellation is not possible (it's too late).
            boolean cancelled = cancel.compareAndSet(false, true);
            task.cancel();
            return cancelled;
        }

        @Override
        public void run()
        {
            if (cancelTimeout())
            {
                session.setScheduler(null);
                if (_logger.isDebugEnabled())
                    _logger.debug("Resuming /meta/connect after timeout");
                resume();
            }
        }

        private void resume()
        {
            decBrowserId(browserId, session);
            dispatch();
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onComplete(AsyncEvent asyncEvent) throws IOException
        {
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        protected abstract void dispatch();

        protected void error(int code)
        {
            decBrowserId(browserId, session);
            AbstractHttpTransport.this.error(getRequest(), getResponse(), getAsyncContext(), code);
        }
    }
}
