package org.cometd.javascript;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.junit.Test;

public class CometDCrossOriginReHandshakeTest extends AbstractCometDTest
{
    private BayeuxServerImpl bayeux;

    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        context.addFilter(new FilterHolder(new CrossOriginFilter()), cometServletPath + "/*", FilterMapping.REQUEST);
        context.addFilter(new FilterHolder(new ConnectThrowingFilter()), cometServletPath + "/*", FilterMapping.REQUEST);
    }

    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        this.bayeux = bayeux;
        bayeux.addExtension(new ReHandshakeExtension());
    }

    @Test
    public void testCrossOriginReHandshakeDoesNotChangeTransportType() throws Exception
    {
        defineClass(Latch.class);
        String crossOriginCometdURL = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("cometd.configure({" +
                       "url: '" + crossOriginCometdURL + "', " +
                       "requestHeaders: { Origin: 'http://localhost:8080' }, " +
                       "logLevel: 'debug'" +
                       "});");
        evaluateScript("var handshakeLatch = new Latch(2);");
        evaluateScript("var connectLatch = new Latch(2);");
        Latch handshakeLatch = get("handshakeLatch");
        Latch connectLatch = get("connectLatch");
        evaluateScript("cometd.addListener('/meta/handshake', handshakeLatch, 'countDown');");
        evaluateScript("cometd.addListener('/meta/connect', connectLatch, 'countDown');");
        evaluateScript("cometd.handshake();");

        Assert.assertTrue(connectLatch.await(longPollingPeriod + 5000));
        Assert.assertTrue(handshakeLatch.await(5000));
        Assert.assertEquals("long-polling", evaluateScript("cometd.getTransport().getType()"));

        evaluateScript("cometd.disconnect(true);");
    }

    private class ReHandshakeExtension implements BayeuxServer.Extension
    {
        private int connects;

        public boolean rcv(ServerSession session, ServerMessage.Mutable message)
        {
            return true;
        }

        public boolean rcvMeta(ServerSession session, ServerMessage.Mutable message)
        {
            return true;
        }

        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message)
        {
            return false;
        }

        public boolean sendMeta(ServerSession session, ServerMessage.Mutable message)
        {
            if (Channel.META_CONNECT.equals(message.getChannel()))
            {
                ++connects;
                if (connects == 1)
                {
                    // Fake the removal of the session due to timeout
                    bayeux.removeServerSession(session, true);
                }
            }
            return true;
        }
    }

    private class ConnectThrowingFilter implements Filter
    {
        private int connects;

        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            String uri = request.getRequestURI();
            if (uri.endsWith("connect"))
            {
                ++connects;
                if (connects == 2)
                    throw new IOException();
            }
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
