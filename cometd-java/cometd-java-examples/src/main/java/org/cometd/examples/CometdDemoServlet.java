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
import java.util.Map;
import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.java.annotation.Configure;
import org.cometd.java.annotation.Listener;
import org.cometd.java.annotation.ServerAnnotationProcessor;
import org.cometd.java.annotation.Service;
import org.cometd.java.annotation.Session;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.server.ext.TimesyncExtension;
import org.eclipse.jetty.util.log.Log;

public class CometdDemoServlet extends GenericServlet
{
    @Override
    public void init() throws ServletException
    {
        super.init();
        final BayeuxServerImpl bayeux=(BayeuxServerImpl)getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);

        if (bayeux==null)
            throw new UnavailableException("No BayeuxServer!");
        
        // Create extensions
        bayeux.addExtension(new TimesyncExtension());
        bayeux.addExtension(new AcknowledgedMessagesExtension());

        // Deny unless granted

        bayeux.createIfAbsent("/**",new ServerChannel.Initializer()
        {
            public void configureChannel(ConfigurableServerChannel channel)
            {
                channel.addAuthorizer(GrantAuthorizer.GRANT_NONE);
            }
        });

        // Allow anybody to handshake
        bayeux.getChannel(ServerChannel.META_HANDSHAKE).addAuthorizer(GrantAuthorizer.GRANT_PUBLISH);

        ServerAnnotationProcessor processor = new ServerAnnotationProcessor(bayeux);
        processor.process(new EchoRPC());
        processor.process(new Monitor());
        // processor.process(new ChatService());

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

    @Override
    public void destroy()
    {
        super.destroy();
    }

    @Service("echo")
    public static class EchoRPC
    {
        @Session
        private ServerSession _session;

        @SuppressWarnings("unused")
        @Configure("/service/echo")
        private void configureEcho(ConfigurableServerChannel channel)
        {
            channel.addAuthorizer(GrantAuthorizer.GRANT_SUBSCRIBE_PUBLISH);
        }

        @Listener("/service/echo")
        public void doEcho(ServerSession session, ServerMessage message)
        {
            Map<String,Object> data = message.getDataAsMap();
            Log.info("ECHO from "+session+" "+data);
            session.deliver(_session, message.getChannel(), data, null);
        }
    }

    @Service("monitor")
    public static class Monitor
    {
        @Listener("/meta/subscribe")
        public void monitorSubscribe(ServerSession session, ServerMessage message)
        {
            Log.info("Monitored Subscribe from "+session+" for "+message.get(Message.SUBSCRIPTION_FIELD));
        }

        @Listener("/meta/unsubscribe")
        public void monitorUnsubscribe(ServerSession session, ServerMessage message)
        {
            Log.info("Monitored Unsubscribe from "+session+" for "+message.get(Message.SUBSCRIPTION_FIELD));
        }

        @Listener("/meta/*")
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
