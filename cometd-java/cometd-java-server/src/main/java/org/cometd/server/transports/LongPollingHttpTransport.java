package org.cometd.server.transports;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;

public class LongPollingHttpTransport extends HttpTransport
{
    public final static String NAME="long-polling";
    
    public LongPollingHttpTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux,NAME);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        ServerMessage.Mutable[] messages = parseMessages(request);

        ServerSessionImpl session=null;
        for (ServerMessage.Mutable message : messages)
        {
            message.incRef();

            _bayeux.recvMessage(session,message);

            ServerMessage reply = message.getAssociated();
            if (reply!=null)
            {
                if (session==null)
                    session=(ServerSessionImpl)_bayeux.getSession(reply.getClientId());

                if (Channel.META_CONNECT.equals(reply.getChannelId()) && reply.isSuccessful())
                {
                    Continuation continuation = ContinuationSupport.getContinuation(request);
                    continuation.suspend();
                    session.setAttribute("continuation",continuation); // TODO - we need atomics here
                    request.setAttribute("connect_reply",reply);
                    request.setAttribute("session",session);
                    reply.incRef();
                }
                else
                {
                    _bayeux.extendReply(session,reply);
                    // TODO send
                }

            }
            message.setAssociated(null);
            message.decRef();
        }

    }
}
