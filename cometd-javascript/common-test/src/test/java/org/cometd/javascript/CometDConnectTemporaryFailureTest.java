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
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;

public class CometDConnectTemporaryFailureTest extends AbstractCometDTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        ConnectThrowingFilter filter = new ConnectThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    @Test
    public void testConnectTemporaryFailure() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("cometd.addListener('/meta/handshake', handshakeLatch, 'countDown');");
        evaluateScript("" +
                "var wasConnected = false;" +
                "var connected = false;" +
                "cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "   window.console.debug('metaConnect: was', wasConnected, 'is', connected, 'message', message.successful);" +
                "   wasConnected = connected;" +
                "   connected = message.successful === true;" +
                "   if (!wasConnected && connected)" +
                "       connectLatch.countDown();" +
                "   else if (wasConnected && !connected)" +
                "       failureLatch.countDown();" +
                "});");

        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(1000));
        Assert.assertTrue(connectLatch.await(1000));
        Assert.assertEquals(1L, failureLatch.jsGet_count());

        handshakeLatch.reset(1);
        connectLatch.reset(1);
        // Wait for the connect to temporarily fail
        Assert.assertTrue(failureLatch.await(longPollingPeriod * 2));
        Assert.assertEquals(1L, handshakeLatch.jsGet_count());
        Assert.assertEquals(1L, connectLatch.jsGet_count());

        // Implementation will backoff the connect attempt
        long backoff = ((Number)evaluateScript("cometd.getBackoffIncrement();")).longValue();
        Thread.sleep(backoff);

        failureLatch.reset(1);
        // Reconnection will trigger /meta/connect
        Assert.assertTrue(connectLatch.await(1000));
        Assert.assertEquals(1L, handshakeLatch.jsGet_count());
        Assert.assertEquals(1L, failureLatch.jsGet_count());

        evaluateScript("cometd.disconnect(true);");
    }

    public static class ConnectThrowingFilter implements Filter
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
                ++connects;
            if (connects == 3)
                throw new IOException();
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
