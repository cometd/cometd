package org.cometd.server.transports;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Queue;

import javax.servlet.ServletException;
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

public class LongPollingHttpTransport extends HttpTransport
{
    public final static String NAME="long-polling";
    
    
    public LongPollingHttpTransport(BayeuxServerImpl bayeux,DefaultTransport dftTransport)
    {
        super(bayeux,dftTransport,NAME);
    }

    @Override
    protected void init()
    {
        super.init();
        
    }
    
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        HttpDispatcher dispatcher=(HttpDispatcher)request.getAttribute("dispatcher");
        if (dispatcher==null)
        {
            ServerMessage.Mutable[] messages = parseMessages(request);

            PrintWriter writer=null;
            ServerSessionImpl session=null;
            
            for (ServerMessage.Mutable message : messages)
            {
                message.incRef();

                // Get the session from the message
                if (session==null)
                    session=(ServerSessionImpl)_bayeux.getSession(message.getClientId());
                boolean connected=session!=null && session.isConnected();
                
                boolean connect = Channel.META_CONNECT.equals(message.getChannelId());
                
                // handle the message
                ServerMessage reply = _bayeux.handle(session,message);
                
                // Do we have a reply
                if (reply!=null)
                {
                    // extract a session from the reply (if we don't already know it
                    if (session==null)
                        session=(ServerSessionImpl)_bayeux.getSession(reply.getClientId());

                    if (session!=null)
                    {
                        // deliver the message
                        if  (connect || !(isMetaConnectDelivery()||session.isMetaConnectDelivery()))
                        {
                            Queue<ServerMessage> queue = session.getQueue();
                            synchronized (queue)
                            {
                                for (int i=queue.size();i-->0;)
                                {
                                    ServerMessage m=queue.poll();
                                    writer=send(response,writer,m);
                                }
                            }
                        }
                    
                        // Should we suspend? 
                        // If the writer is non null, we have already started sending a response, so we should not suspend
                        if (connect && connected && writer==null && reply.isSuccessful())
                        {
                            Continuation continuation = ContinuationSupport.getContinuation(request);
                            long timeout=session.getTimeout();
                            continuation.setTimeout(timeout==-1?_timeout:timeout); 
                            continuation.suspend();
                            dispatcher=new HttpDispatcher(session,continuation,reply);
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
                                reply=_bayeux.extendReply(session,reply);
                                writer=send(response,writer,reply);
                            }
                        }
                        else
                        {
                            reply=_bayeux.extendReply(session,reply);
                            writer=send(response,writer,reply);
                        }
                    }
                }
                message.setAssociated(null);
                message.decRef();
            }
            if (writer!=null)
                complete(writer);
        }
        else
        {
            // Get the resumed session
            ServerSessionImpl session=dispatcher.getSession();
            
            // Send the message queue
            Queue<ServerMessage> queue = session.getQueue();
            PrintWriter writer=null;
            synchronized (queue)
            {
                for (int i=queue.size();i-->0;)
                {
                    ServerMessage m=queue.poll();
                    writer=send(response,writer,m);
                }
            }
            
            // send the connect reply
            ServerMessage reply=dispatcher.getReply();
            reply=_bayeux.extendReply(session,reply);
            writer=send(response,writer,reply);
            
            complete(writer);
        }
    }
    
    public boolean isMetaConnectDelivery()
    {
        // TODO Auto-generated method stub
        return true;
    }

    protected PrintWriter send(HttpServletResponse response,PrintWriter writer,ServerMessage message) throws IOException
    {
        if (writer==null)
        {
            response.setContentType("application/json;charset=UTF-8");
            writer = response.getWriter();
            writer.append('['); 
        }
        else
            writer.append(','); 
        writer.append(message.getJSON());
        return writer;
    }
    
    protected void complete(PrintWriter writer) throws IOException
    {
        writer.append("]\n");
        writer.close();
    }
    
    private class HttpDispatcher implements ServerTransport.Dispatcher, ContinuationListener
    {
        private final ServerSessionImpl _session;
        private final Continuation _continuation;
        private final ServerMessage _reply;
        
        public HttpDispatcher(ServerSessionImpl session, Continuation continuation, ServerMessage reply)
        {
            _session = session;
            _continuation = continuation;
            _continuation.addContinuationListener(this);
            _reply = reply;
            reply.incRef();
        }

        public void cancel()
        {
            _continuation.complete();
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
        }

        public void onTimeout(Continuation continuation)
        {
            _session.setDispatcher(null);
        }
        
    }
}
