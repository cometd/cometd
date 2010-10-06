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
import java.util.EnumSet;

import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.Authorizer;
import org.cometd.bayeux.server.Authorizer.Operation;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.annotation.CometdService;
import org.cometd.server.annotation.Inject;
import org.cometd.server.annotation.Subscription;
import org.cometd.server.authorizer.ChannelAuthorizer;
import org.cometd.server.authorizer.GrantAuthorizer;
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

        // Create extensions
        bayeux.addExtension(new TimesyncExtension());
        bayeux.addExtension(new AcknowledgedMessagesExtension());
        
        // Allow anybody to handshake
        bayeux.addAuthorizer(new GrantAuthorizer(EnumSet.of(Authorizer.Operation.HANDSHAKE)));
        
        // Create and register services
        AbstractService.register(bayeux,new EchoRPC());
        AbstractService.register(bayeux,new Monitor());
        AbstractService.register(bayeux,new ChatService());
        

        bayeux.createIfAbsent("/foo/bar/baz",new ConfigurableServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.setPersistent(true);
            }
        });

        if (bayeux.getLogger().isDebugEnabled())
            System.err.println(bayeux.dump());
    }

    @CometdService(name="echo")
    public static class EchoRPC 
    {
        @Inject
        protected void init(BayeuxServer bayeux)
        {
            bayeux.addAuthorizer(new ChannelAuthorizer(EnumSet.of(Operation.PUBLISH),"/service/echo"));
        }

        @Subscription(channels="/service/echo")
        public Object doEcho(ServerSession session, Object data)
        {
            Log.info("ECHO from "+session+" "+data);
            return data;
        }
    }

    @CometdService(name="monitor")
    public static class Monitor
    {
        @Subscription(channels="/meta/subscribe")
        public void monitorSubscribe(ServerSession session, ServerMessage message)
        {
            Log.info("Monitored Subscribe from "+session+" for "+message.get(Message.SUBSCRIPTION_FIELD));
        }

        @Subscription(channels="/meta/unsubscribe")
        public void monitorUnsubscribe(ServerSession session, ServerMessage message)
        {
            Log.info("Monitored Unsubscribe from "+session+" for "+message.get(Message.SUBSCRIPTION_FIELD));
        }

        @Subscription(channels="/meta/*")
        public void monitorMeta(ServerSession session, ServerMessage message)
        {
            if (Log.isDebugEnabled())
                Log.debug(message.toString());
        }
    }

    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
    {
        ((HttpServletResponse)res).sendError(503);
    }
}
