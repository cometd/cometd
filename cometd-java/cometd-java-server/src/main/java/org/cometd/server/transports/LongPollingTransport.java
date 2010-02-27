package org.cometd.server.transports;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.ServerTransport;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.util.log.Log;

public abstract class LongPollingTransport extends HttpTransport
{
    protected final static String BROWSER_ID_OPTION="browserId";
    protected final static String MAX_SESSIONS_PER_BROWSER_OPTION="maxSessionsPerBrowser";
    protected final static String MULTI_SESSION_INTERVAL_OPTION="multiSessionInterval";

    private final ConcurrentHashMap<String, AtomicInteger> _browserMap=new ConcurrentHashMap<String, AtomicInteger>();

    protected String _browserId="BAYEUX_BROWSER";
    private int _maxSessionsPerBrowser=1;
    private long _multiSessionInterval=2000;
    
    protected LongPollingTransport(BayeuxServerImpl bayeux,String name,Map<String,Object> options)
    {
        super(bayeux,name,options);
        _prefix.add("long-polling");
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

        String browser_id=Long.toHexString(request.getRemotePort()) + Long.toString(_bayeux.randomLong(),36) + Long.toString(System.currentTimeMillis(),36)
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
        
        if (count.incrementAndGet()>_maxSessionsPerBrowser)
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
        if (count!=null)
            count.decrementAndGet();
    }
    
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // is this a resumed connect?
        LongPollDispatcher dispatcher=(LongPollDispatcher)request.getAttribute("dispatcher");
        if (dispatcher==null)
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
                    // reference it (this should be ref=1)
                    message.incRef();

                    // Get the session from the message
                    if (session==null)
                    {
                        session=(ServerSessionImpl)_bayeux.getSession(message.getClientId());
                        if (session!=null && !message.isMeta())
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
                    ServerMessage reply = _bayeux.handle(session,message);

                    // Do we have a reply
                    if (reply!=null)
                    {
                        if (session==null)
                            // This must be a handshake
                            // extract a session from the reply (if we don't already know it
                            session=(ServerSessionImpl)_bayeux.getSession(reply.getClientId());
                        else
                        {
                            // If this is a connect or we can send messages with any response
                            if  (connect || !(isMetaConnectDeliveryOnly()||session.isMetaConnectDeliveryOnly()))
                            {
                                // send the queued messages
                                Queue<ServerMessage> queue = session.getQueue();
                                synchronized (queue)
                                {
                                    session.dequeue();
                                    for (int i=queue.size();i-->0;)
                                    {
                                        ServerMessage m=queue.poll();
                                        writer=send(request,response,writer, m);
                                    }
                                }
                            }

                            // special handling for connect
                            if (connect)
                            {
                                // Should we suspend? 
                                // If the writer is non null, we have already started sending a response, so we should not suspend
                                if(was_connected && writer==null && reply.isSuccessful())
                                {
                                    session.cancelDispatch();
                                    String browserId=getBrowserId(request,response);
                                    if (incBrowserId(browserId,request,reply))
                                    {
                                        Continuation continuation = ContinuationSupport.getContinuation(request);
                                        long timeout=session.getTimeout();
                                        continuation.setTimeout(timeout==-1?_timeout:timeout); 
                                        continuation.suspend();
                                        dispatcher=new LongPollDispatcher(session,continuation,reply,browserId);
                                        if (session.setDispatcher(dispatcher))
                                        {
                                            // suspend successful
                                            request.setAttribute("dispatcher",dispatcher);
                                            reply=null;
                                        }
                                        else
                                        {
                                            // a message was already added - so handle it now;
                                            continuation.complete();
                                            dispatcher=null;
                                            if (session.isConnected())
                                                session.startIntervalTimeout();
                                        }
                                    }
                                    else 
                                    {
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
                            reply=_bayeux.extendReply(session,reply);
                            
                            if (reply!=null)
                                writer=send(request,response,writer, reply);
                        }
                    }
                    
                    // disassociate the reply
                    message.setAssociated(null);
                    // dec our own ref, this should be to 0 unless message was ref'd elsewhere.
                    message.decRef();
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
            ServerSessionImpl session=dispatcher.getSession();
            if (session.isConnected())
                session.startIntervalTimeout();

            // Send the message queue
            Queue<ServerMessage> queue = session.getQueue();
            PrintWriter writer=null;
            synchronized (queue)
            {
                session.dequeue();
                for (int i=queue.size();i-->0;)
                {
                    ServerMessage m=queue.poll();
                    writer=send(request,response,writer, m);
                }
            }
            
            // send the connect reply
            ServerMessage reply=dispatcher.getReply();
            reply=_bayeux.extendReply(session,reply);
            writer=send(request,response,writer, reply);
            
            complete(writer);
        }
    }

    abstract protected PrintWriter send(HttpServletRequest request,HttpServletResponse response,PrintWriter writer, ServerMessage message) throws IOException;
    
    abstract protected void complete(PrintWriter writer) throws IOException;
    
    
    private class LongPollDispatcher implements ServerTransport.Dispatcher, ContinuationListener
    {
        private final ServerSessionImpl _session;
        private final Continuation _continuation;
        private final ServerMessage _reply;
        private final String _browserId;
        
        public LongPollDispatcher(ServerSessionImpl session, Continuation continuation, ServerMessage reply,String browserId)
        {
            _session = session;
            _continuation = continuation;
            _continuation.addContinuationListener(this);
            _reply = reply;
            reply.incRef();
            _browserId=browserId;
        }

        public void cancelDispatch()
        {
            if (_continuation!=null && _continuation.isSuspended() )
            {
                try
                {
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

        public void dispatch()
        {
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
            decBrowserId(_browserId);
        }

        public void onTimeout(Continuation continuation)
        {
            _session.setDispatcher(null);
        }
        
    }
}
