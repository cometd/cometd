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

public class CometDConnectFailureTest extends AbstractCometDTest
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
    public void testConnectFailure() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("cometd.addListener('/meta/handshake', handshakeLatch, handshakeLatch.countDown);");
        evaluateScript("cometd.addListener('/meta/unsuccessful', failureLatch, failureLatch.countDown);");
        evaluateScript("cometd.addListener('/meta/connect', connectLatch, connectLatch.countDown);");

        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        evaluateScript("var backoffIncrement = cometd.getBackoffIncrement();");
        int backoff = ((Number)get("backoff")).intValue();
        final int backoffIncrement = ((Number)get("backoffIncrement")).intValue();
        Assert.assertEquals(0, backoff);
        Assert.assertTrue(backoffIncrement > 0);

        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(1000));
        Assert.assertTrue(connectLatch.await(1000));
        Assert.assertTrue(failureLatch.await(1000));

        // There is a failure, the backoff will be increased from 0 to backoffIncrement
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        backoff = ((Number)get("backoff")).intValue();
        Assert.assertEquals(backoffIncrement, backoff);

        connectLatch.reset(1);
        failureLatch.reset(1);
        Assert.assertTrue(connectLatch.await(backoffIncrement));
        Assert.assertTrue(failureLatch.await(backoffIncrement));

        // Another failure, backoff will be increased to 2 * backoffIncrement
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = cometd.getBackoffPeriod();");
        backoff = ((Number)get("backoff")).intValue();
        Assert.assertEquals(2 * backoffIncrement, backoff);

        connectLatch.reset(1);
        failureLatch.reset(1);
        Assert.assertTrue(connectLatch.await(2 * backoffIncrement));
        Assert.assertTrue(failureLatch.await(2 * backoffIncrement));

        // Disconnect so that connect is not performed anymore
        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        failureLatch.reset(1);
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(1000));
        Assert.assertTrue(failureLatch.await(1000));
        String status = evaluateScript("cometd.getStatus();");
        Assert.assertEquals("disconnected", status);

        // Be sure the connect is not retried anymore
        connectLatch.reset(1);
        Assert.assertFalse(connectLatch.await(4 * backoffIncrement));
    }

    public static class ConnectThrowingFilter implements Filter
    {
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
                throw new IOException();
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
