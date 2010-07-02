package org.cometd.server.transport;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.ParseException;
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
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.util.log.Log;


/* ------------------------------------------------------------ */
/** Abstract Long Polling Transport.
 * <p>
 * Transports based on this class can be configured with servlet init parameters:<dl>
 * <dt>browserId</dt><dd>The Cookie name used to save a browser ID.</dd>
 * <dt>maxSessionsPerBrowser</dt><dd>The maximum number of long polling sessions allowed per browser.</dd>
 * <dt>multiSessionInterval</dt><dd>The polling interval to use once max session per browser is exceeded.</dd>
 * <dt>autoBatch</dt><dd>If true a batch will be automatically created to span the handling of messages received from a session.</dd>
 * </dl>
 *
 */
public abstract class LongPollingTransport extends HttpTransport
{
    public final static String PREFIX="long-polling";
    public final static String BROWSER_ID_OPTION="browserId";
    public final static String MAX_SESSIONS_PER_BROWSER_OPTION="maxSessionsPerBrowser";
    public final static String MULTI_SESSION_INTERVAL_OPTION="multiSessionInterval";
    public final static String AUTOBATCH_OPTION="autoBatch";

    private final ConcurrentHashMap<String, AtomicInteger> _browserMap=new ConcurrentHashMap<String, AtomicInteger>();

    protected String _browserId="BAYEUX_BROWSER";
    private int _maxSessionsPerBrowser=1;
    private long _multiSessionInterval=2000;
    private boolean _autoBatch=true;

    protected LongPollingTransport(BayeuxServerImpl bayeux,String name)
    {
        super(bayeux,name);
        setOptionPrefix(PREFIX);
        setOption(BROWSER_ID_OPTION,_browserId);
        setOption(MAX_SESSIONS_PER_BROWSER_OPTION,_maxSessionsPerBrowser);
        setOption(MULTI_SESSION_INTERVAL_OPTION,_multiSessionInterval);
        setOption(AUTOBATCH_OPTION,_autoBatch);
    }

    @Override
    protected void init()
    {
        super.init();
        _browserId=getOption(BROWSER_ID_OPTION,_browserId);
        _maxSessionsPerBrowser=getOption(MAX_SESSIONS_PER_BROWSER_OPTION,_maxSessionsPerBrowser);
        _multiSessionInterval=getOption(MULTI_SESSION_INTERVAL_OPTION,_multiSessionInterval);
        _autoBatch=getOption(AUTOBATCH_OPTION,_autoBatch);
    }

    protected String getBrowserId(HttpServletRequest request, HttpServletResponse response)
    {
        Cookie[] cookies=request.getCookies();
        if (cookies != null)
        {
            for (Cookie cookie : cookies)
            {
                if (_browserId.equals(cookie.getName()))
                    return cookie.getValue();
            }
        }

        String browser_id=Long.toHexString(request.getRemotePort()) + Long.toString(getBayeux().randomLong(),36) + Long.toString(System.currentTimeMillis(),36)
                + Long.toString(request.getRemotePort(),36);
        Cookie cookie=new Cookie(_browserId,browser_id);
        cookie.setPath("/");
        cookie.setMaxAge(-1);
        response.addCookie(cookie);
        return browser_id;
    }

    protected boolean addBrowserSession(String browserId, String clientId)
    {
        if (_maxSessionsPerBrowser < 0)
            return true;
        if (_maxSessionsPerBrowser == 0)
            return false;

        AtomicInteger sessions = _browserMap.get(browserId);
        if (sessions == null)
        {
            AtomicInteger newSessions = new AtomicInteger();
            sessions = _browserMap.putIfAbsent(browserId, newSessions);
            if (sessions == null)
                sessions = newSessions;
        }

        // Synchronization is necessary to avoid modifying
        // a structure that has been removed from the map
        synchronized (sessions)
        {
            // The entry could have been removed concurrently by removeBrowserSession()
            if (!_browserMap.containsKey(browserId))
                _browserMap.put(browserId, sessions);

            // TODO, the maxSessionsPerBrowser should be parametrized on user-agent
            if (sessions.getAndIncrement() < _maxSessionsPerBrowser)
            {
                return true;
            }

            return false;
        }
    }

    protected boolean removeBrowserSession(String browserId, String clientId)
    {
        AtomicInteger sessions = _browserMap.get(browserId);
        if (sessions != null)
        {
            // Synchronization is necessary to avoid modifying the
            // structure after the if statement but before the remove call
            synchronized (sessions)
            {
                if (sessions.decrementAndGet() == 0)
                {
                    _browserMap.remove(browserId, sessions);
                    return true;
                }
            }
        }
        return false;
    }

    protected AtomicInteger getBrowserSessions(String browserId)
    {
        return _browserMap.get(browserId);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // is this a resumed connect?
        LongPollScheduler scheduler=(LongPollScheduler)request.getAttribute("cometd.scheduler");
        if (scheduler==null)
        {
            // No - process messages

            // remember if we start a batch
            boolean batch=false;

            // Don't know the session until first message or handshake response.
            ServerSessionImpl session=null;
            boolean connect=false;

            try
            {
                ServerMessage.Mutable[] messages = parseMessages(request);
                if (messages==null)
                    return;

                PrintWriter writer=null;

                // for each message
                for (ServerMessage.Mutable message : messages)
                {
                    // Is this a connect?
                    connect = Channel.META_CONNECT.equals(message.getChannel());

                    // Get the session from the message
                    String client_id=message.getClientId();
                    if (session==null || client_id!=null && !client_id.equals(session.getId()))
                    {
                        session=(ServerSessionImpl)getBayeux().getSession(client_id);
                        if (_autoBatch && !batch && session!=null && !connect && !message.isMeta())
                        {
                            // start a batch to group all resulting messages into a single response.
                            batch=true;
                            session.startBatch();
                        }
                    }
                    else if (!session.isHandshook())
                    {
                        batch=false;
                        session=null;
                    }

                    if (connect && session!=null)
                    {
                        // cancel previous scheduler to cancel any prior waiting long poll
                        // this should also dec the Browser ID
                        session.setScheduler(null);
                    }

                    // remember the connected status
                    boolean was_connected=session!=null && session.isConnected();

                    // handle the message
                    // the actual reply is return from the call, but other messages may
                    // also be queued on the session.
                    ServerMessage reply = getBayeux().handle(session,message);

                    // Do we have a reply
                    if (reply!=null)
                    {
                        if (session==null)
                        {
                            // This must be a handshake
                            // extract a session from the reply (if we don't already know it
                            session=(ServerSessionImpl)getBayeux().getSession(reply.getClientId());

                            // get the user agent while we are at it.
                            if (session!=null)
                                session.setUserAgent(request.getHeader("User-Agent"));
                        }
                        else
                        {
                            // If this is a connect or we can send messages with any response
                            if  (connect || !(isMetaConnectDeliveryOnly()||session.isMetaConnectDeliveryOnly()))
                            {
                                // send the queued messages
                                writer=sendQueue(request,response,session,writer);
                            }

                            // special handling for connect
                            if (connect)
                            {
                                long timeout = session.calculateTimeout(getTimeout());

                                // Should we suspend?
                                // If the writer is non null, we have already started sending a response, so we should not suspend
                                if(timeout>0 && was_connected && writer==null && reply.isSuccessful() && session.isQueueEmpty())
                                {
                                    // If we don't have too many long polls from this browser
                                    String browserId=getBrowserId(request,response);
                                    if (addBrowserSession(browserId, session.getId()))
                                    {
                                        // suspend and wait for messages
                                        Continuation continuation = ContinuationSupport.getContinuation(request);
                                        continuation.setTimeout(timeout);
                                        continuation.suspend(response);
                                        scheduler=new LongPollScheduler(session,continuation,reply,browserId);
                                        session.setScheduler(scheduler);
                                        request.setAttribute("cometd.scheduler",scheduler);
                                        reply=null;
                                    }
                                    else
                                    {
                                        // Advise multiple clients from same browser
                                        Map<String, Object> advice = reply.asMutable().getAdvice(true);
                                        advice.put("multiple-clients", true);
                                        if (_multiSessionInterval > 0)
                                        {
                                            advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
                                            advice.put(Message.INTERVAL_FIELD, _multiSessionInterval);
                                        }
                                        else
                                        {
                                            advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                                        }
                                        session.reAdvise();
                                    }
                                }
                                else if (session.isConnected())
                                {
                                    session.startIntervalTimeout();
                                }
                            }
                        }

                        // If the reply has not been otherwise handled, send it
                        if (reply!=null)
                        {
                            reply=getBayeux().extendReply(session,session,reply);

                            if (reply!=null)
                                writer=send(request,response,writer, reply);
                        }
                    }

                    // disassociate the reply
                    message.setAssociated(null);
                }
                if (writer!=null)
                    complete(writer);
            }
            catch (ParseException x)
            {
                handleJSONParseException(request, response, x.getMessage(), x.getCause());
            }
            finally
            {
                // if we started a batch - end it now
                if (batch)
                {
                    boolean ended=session.endBatch();

                    // flush session if not done by the batch
                    // since some browsers well order script gets
                    if (!ended && isAlwaysFlushingAfterHandle())
                        session.flush();
                }
                else if (session!=null && !connect && isAlwaysFlushingAfterHandle())
                    session.flush();
            }
        }
        else
        {
            // Get the resumed session
            ServerSessionImpl session=scheduler.getSession();
            if (session.isConnected())
                session.startIntervalTimeout();

            // Send the message queue
            PrintWriter writer=sendQueue(request,response,session,null);

            // send the connect reply
            ServerMessage reply=scheduler.getReply();
            reply=getBayeux().extendReply(session,session,reply);
            writer=send(request,response,writer, reply);

            complete(writer);
        }
    }

    protected void handleJSONParseException(HttpServletRequest request, HttpServletResponse response, String json, Throwable exception) throws ServletException, IOException
    {
        getBayeux().getLogger().debug("Error parsing JSON: " + json, exception);
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
    }

    private PrintWriter sendQueue(HttpServletRequest request, HttpServletResponse response,ServerSessionImpl session, PrintWriter writer)
        throws IOException
    {
        final List<ServerMessage> queue = session.takeQueue();
        for (ServerMessage m:queue)
            writer=send(request,response,writer, m);
        return writer;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if the transport always flushes at the end of a call to {@link #handle(HttpServletRequest, HttpServletResponse)}.
     */
    abstract protected boolean isAlwaysFlushingAfterHandle();

    abstract protected PrintWriter send(HttpServletRequest request,HttpServletResponse response,PrintWriter writer, ServerMessage message) throws IOException;

    abstract protected void complete(PrintWriter writer) throws IOException;


    private class LongPollScheduler implements AbstractServerTransport.OneTimeScheduler, ContinuationListener
    {
        private final ServerSessionImpl _session;
        private final Continuation _continuation;
        private final ServerMessage _reply;
        private String _browserId;

        public LongPollScheduler(ServerSessionImpl session, Continuation continuation, ServerMessage reply,String browserId)
        {
            _session = session;
            _continuation = continuation;
            _continuation.addContinuationListener(this);
            _reply = reply;
            _browserId=browserId;
        }

        public void cancel()
        {
            if (_continuation!=null && _continuation.isSuspended() && !_continuation.isExpired())
            {
                try
                {
                    decBrowserId();
                    HttpServletResponse response = (HttpServletResponse)_continuation.getServletResponse();
                    response.sendError(HttpServletResponse.SC_REQUEST_TIMEOUT);
                }
                catch(IOException e)
                {
                    Log.ignore(e);
                }

                try
                {
                    _continuation.complete();
                }
                catch(Exception e)
                {
                    Log.ignore(e);
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

        public Continuation getContinuation()
        {
            return _continuation;
        }

        public ServerMessage getReply()
        {
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
            synchronized (this)
            {
                if (_browserId!=null)
                {
                    LongPollingTransport.this.removeBrowserSession(_browserId, _session.getId());
                    _browserId=null;
                }
            }
        }
    }
}
