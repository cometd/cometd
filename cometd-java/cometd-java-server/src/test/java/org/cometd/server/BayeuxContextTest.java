package org.cometd.server;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.FilterHolder;
import org.junit.Assert;
import org.junit.Test;

public class BayeuxContextTest extends AbstractBayeuxClientServerTest
{
    @Test
    public void testAddresses() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener()
        {
            public void sessionAdded(ServerSession session)
            {
                Assert.assertNotNull(bayeux.getContext().getLocalAddress());
                Assert.assertNotNull(bayeux.getContext().getRemoteAddress());
                latch.countDown();
            }

            public void sessionRemoved(ServerSession session, boolean timedout)
            {
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestHeader() throws Exception
    {
        final String name = "test";
        final String value1 = "foo";
        final String value2 = "bar";
        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener()
        {
            public void sessionAdded(ServerSession session)
            {
                Assert.assertEquals(value1, bayeux.getContext().getHeader(name));
                Assert.assertEquals(Arrays.asList(value1, value2), bayeux.getContext().getHeaderValues(name));
                latch.countDown();
            }

            public void sessionRemoved(ServerSession session, boolean timedout)
            {
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        handshake.header(name, value1);
        handshake.header(name, value2);
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestAttribute() throws Exception
    {
        final String name = "test";
        final String value = "foo";

        context.stop();
        context.addFilter(new FilterHolder(new Filter()
        {
            public void init(FilterConfig filterConfig) throws ServletException
            {
            }

            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException
            {
                servletRequest.setAttribute(name, value);
                filterChain.doFilter(servletRequest, servletResponse);
            }

            public void destroy()
            {
            }
        }), "/*", EnumSet.of(DispatcherType.REQUEST));
        context.start();
        bayeux = cometdServlet.getBayeux();

        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener()
        {
            public void sessionAdded(ServerSession session)
            {
                Assert.assertEquals(value, bayeux.getContext().getRequestAttribute(name));
                latch.countDown();
            }

            public void sessionRemoved(ServerSession session, boolean timedout)
            {
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSessionAttribute() throws Exception
    {
        final String name = "test";
        final String value = "foo";

        context.stop();
        context.addFilter(new FilterHolder(new Filter()
        {
            public void init(FilterConfig filterConfig) throws ServletException
            {
            }

            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException
            {
                HttpServletRequest request = (HttpServletRequest)servletRequest;
                request.getSession(true).setAttribute(name, value);
                filterChain.doFilter(servletRequest, servletResponse);
            }

            public void destroy()
            {
            }
        }), "/*", EnumSet.of(DispatcherType.REQUEST));
        context.start();
        bayeux = cometdServlet.getBayeux();

        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener()
        {
            public void sessionAdded(ServerSession session)
            {
                Assert.assertNotNull(bayeux.getContext().getHttpSessionId());
                Assert.assertEquals(value, bayeux.getContext().getHttpSessionAttribute(name));
                latch.countDown();
            }

            public void sessionRemoved(ServerSession session, boolean timedout)
            {
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testContextAttribute() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        bayeux.addListener(new BayeuxServer.SessionListener()
        {
            public void sessionAdded(ServerSession session)
            {
                Assert.assertSame(bayeux, bayeux.getContext().getContextAttribute(BayeuxServer.ATTRIBUTE));
                latch.countDown();
            }

            public void sessionRemoved(ServerSession session, boolean timedout)
            {
            }
        });

        Request handshake = newBayeuxRequest("[{" +
                "\"channel\": \"/meta/handshake\"," +
                "\"version\": \"1.0\"," +
                "\"minimumVersion\": \"1.0\"," +
                "\"supportedConnectionTypes\": [\"long-polling\"]" +
                "}]");
        ContentResponse response = handshake.send();
        Assert.assertEquals(200, response.getStatus());

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
