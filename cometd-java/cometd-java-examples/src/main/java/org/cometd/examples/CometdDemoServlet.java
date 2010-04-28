// ========================================================================
// Copyright 2007-2008 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.cometd.examples;



import java.io.IOException;

import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.BayeuxService;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.server.ext.TimesyncExtension;
import org.eclipse.jetty.util.log.Log;

public class CometdDemoServlet extends GenericServlet
{
    public CometdDemoServlet()
    {
    }

    @Override
    public void init() throws ServletException
    {
        super.init();
        final BayeuxServerImpl bayeux=(BayeuxServerImpl)getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
        new EchoRPC(bayeux);
        new Monitor(bayeux);
        new ChatService(bayeux);
        bayeux.addExtension(new TimesyncExtension());
        bayeux.addExtension(new AcknowledgedMessagesExtension());
        
        if (bayeux.getLogger().isDebugEnabled())
            System.err.println(bayeux.dump());
    }

    public static class EchoRPC extends BayeuxService
    {
        public EchoRPC(BayeuxServer bayeux)
        {
            super(bayeux,"echo");
            subscribe("/service/echo","doEcho");
        }

        public Object doEcho(ServerSession session, Object data)
        {
	    Log.info("ECHO from "+session+" "+data);
	    return data;
        }
    }

    public static class Monitor extends BayeuxService
    {
        public Monitor(BayeuxServer bayeux)
        {
            super(bayeux,"monitor");
            subscribe("/meta/subscribe","monitorSubscribe");
            subscribe("/meta/unsubscribe","monitorUnsubscribe");
            subscribe("/meta/*","monitorMeta");
        }

        public void monitorSubscribe(ServerSession session, ServerMessage message)
        {
            Log.info("Subscribe from "+session+" for "+message.get(Message.SUBSCRIPTION_FIELD));
        }

        public void monitorUnsubscribe(ServerSession session, ServerMessage message)
        {
            Log.info("Unsubscribe from "+session+" for "+message.get(Message.SUBSCRIPTION_FIELD));
        }

        public void monitorMeta(ServerSession session, ServerMessage message)
        {
            if (Log.isDebugEnabled())
                Log.debug(message.toString());
        }

        /*
        public void monitorVerbose(Client client, Message message)
        {
            System.err.println(message);
            try
            {
                Thread.sleep(5000);
            }
            catch(Exception e)
            {
                Log.warn(e);
            }
        }
        */
    }

    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        ((HttpServletResponse)res).sendError(503);
    }
}
