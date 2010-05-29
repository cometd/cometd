package org.cometd.server.transport;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.util.log.Log;

public abstract class LongPollingTransport extends HttpTransport
{
    private final static AtomicInteger __zero = new AtomicInteger(0);

    public final static String PREFIX="long-polling";
    public final static String BROWSER_ID_OPTION="browserId";
    public final static String MAX_SESSIONS_PER_BROWSER_OPTION="maxSessionsPerBrowser";
    public final static String MULTI_SESSION_INTERVAL_OPTION="multiSessionInterval";
    public final static String LONGPOLLS_PER_BROWSER_OPTION="longPollsPerBrowser";

    private final ConcurrentHashMap<String, AtomicInteger> _browserMap=new ConcurrentHashMap<String, AtomicInteger>();

    protected String _browserId="BAYEUX_BROWSER";
    private int _maxSessionsPerBrowser=2;
    private long _multiSessionInterval=2000;

    protected LongPollingTransport(BayeuxServerImpl bayeux,String name)
    {
        super(bayeux,name);
        setOptionPrefix(PREFIX);
        setOption(BROWSER_ID_OPTION,_browserId);
        setOption(MAX_SESSIONS_PER_BROWSER_OPTION,_maxSessionsPerBrowser);
        setOption(MULTI_SESSION_INTERVAL_OPTION,_multiSessionInterval);
    }

    @Override
    protected void init()
    {
        super.init();
        _browserId=getOption(BROWSER_ID_OPTION,_browserId);
        _maxSessionsPerBrowser=getOption(MAX_SESSIONS_PER_BROWSER_OPTION,_maxSessionsPerBrowser);
        _multiSessionInterval=getOption(MULTI_SESSION_INTERVAL_OPTION,_multiSessionInterval);
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

    protected boolean incBrowserId(String browserId,HttpServletRequest request, ServerMessage reply)
    {
        if (_maxSessionsPerBrowser<0)
            return true;

        AtomicInteger count = _browserMap.get(browserId);
        if (count==null)
        {
            AtomicInteger new_count = new AtomicInteger();
            count=_browserMap.putIfAbsent(browserId,new_count);
            if (count==null)
                count=new_count;
        }

        // TODO, the maxSessionsPerBrowser should be parameterized on user-agent
        int sessions=count.incrementAndGet();
        if (sessions>_maxSessionsPerBrowser)
        {
            Map<String,Object> advice=reply.asMutable().getAdvice(true);
            advice.put("multiple-clients",Boolean.TRUE);
            if (_multiSessionInterval > 0)
            {
                advice.put("reconnect","retry");
                advice.put("interval",_multiSessionInterval);
            }
            else
                advice.put("reconnect","none");
            count.decrementAndGet();
            return false;
        }

        return true;
    }

    protected void decBrowserId(String browserId)
    {
        AtomicInteger count = _browserMap.get(browserId);
        if (count!=null && count.decrementAndGet()==0)
        {
            _browserMap.remove(browserId,__zero);
        }
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

            try
            {
                ServerMessage.Mutable[] messages = parseMessages(request);
                if (messages==null)
                    return;

                PrintWriter writer=null;

                // for each message
                for (ServerMessage.Mutable message : messages)
                {
                    // Get the session from the message
                    if (session==null)
                    {
                        session=(ServerSessionImpl)getBayeux().getSession(message.getClientId());
                        if (!batch && session!=null && !message.isMeta())
                        {
                            // start a batch to group all resulting messages into a single response.
                            batch=true;
                            session.startBatch();
                        }
                    }

                    // remember the connected status
                    boolean was_connected=session!=null && session.isConnected();
                    boolean connect = Channel.META_CONNECT.equals(message.getChannel());

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
                                long timeout = session.getTimeout();
                                if (timeout<0)
                                    timeout = getTimeout();
                                
                                // Should we suspend?
                                // If the writer is non null, we have already started sending a response, so we should not suspend
                                if(timeout>0 && was_connected && writer==null && reply.isSuccessful() && session.isQueueEmpty())
                                {   
                                    // cancel previous scheduler to cancel any prior waiting long poll
                                    // this should also dec the Browser ID
                                    session.setScheduler(null);
                                    
                                    // If we don't have too many long polls from this browser
                                    String browserId=getBrowserId(request,response);
                                    if (incBrowserId(browserId,request,reply))
                                    {
                                        // suspend and wait for messages
                                        Continuation continuation = ContinuationSupport.getContinuation(request);
                                        continuation.setTimeout(timeout);
                                        continuation.suspend();
                                        scheduler=new LongPollScheduler(session,continuation,reply,browserId);
                                        session.setScheduler(scheduler);
                                        request.setAttribute("cometd.scheduler",scheduler);
                                        reply=null;
                                    }
                                    else
                                    {
                                        // Advise multiple clients from browser
                                        session.reAdvise();
                                    }
                                }
                                else if (session.isConnected())
                                    session.startIntervalTimeout();
                            }
                        }

                        // If the reply has not been otherwise handled, send it
                        if (reply!=null)
                        {
                            reply=getBayeux().extendReply(session,reply);

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
            finally
            {
                // if we started a batch - end it now
                if (batch)
                    session.endBatch();
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
            reply=getBayeux().extendReply(session,reply);
            writer=send(request,response,writer, reply);

            complete(writer);
        }
    }

    private PrintWriter sendQueue(HttpServletRequest request, HttpServletResponse response,ServerSessionImpl session, PrintWriter writer)
        throws IOException
    {
        final List<ServerMessage> queue = session.takeQueue();
        for (ServerMessage m:queue)
            writer=send(request,response,writer, m);
        return writer;
    }

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
                    ((HttpServletResponse)_continuation.getServletResponse()).sendError(503);
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
                    LongPollingTransport.this.decBrowserId(_browserId);
                    _browserId=null;
                }
            }
        }
    }
}
