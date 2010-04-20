package org.cometd.javascript.jquery;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.javascript.Latch;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdConnectTemporaryFailureTest extends AbstractCometdJQueryTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        ConnectThrowingFilter filter = new ConnectThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    public void testConnectTemporaryFailure() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("$.cometd.addListener('/meta/handshake', handshakeLatch, 'countDown');");
        evaluateScript("" +
                "var wasConnected = false;" +
                "var connected = false;" +
                "$.cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "   window.console.debug('metaConnect: was', wasConnected, 'is', connected, 'message', message.successful);" +
                "   wasConnected = connected;" +
                "   connected = message.successful === true;" +
                "   if (!wasConnected && connected)" +
                "       connectLatch.countDown();" +
                "   else if (wasConnected && !connected)" +
                "       failureLatch.countDown();" +
                "});");

        evaluateScript("$.cometd.handshake();");
        assertTrue(handshakeLatch.await(1000));
        assertTrue(connectLatch.await(1000));
        assertEquals(1L, failureLatch.jsGet_count());

        handshakeLatch.reset(1);
        connectLatch.reset(1);
        // Wait for the connect to temporarily fail
        assertTrue(failureLatch.await(longPollingPeriod * 2));
        assertEquals(1L, handshakeLatch.jsGet_count());
        assertEquals(1L, connectLatch.jsGet_count());

        // Implementation will backoff the connect attempt
        long backoff = ((Number)evaluateScript("$.cometd.getBackoffIncrement();")).longValue();
        Thread.sleep(backoff);

        failureLatch.reset(1);
        // Reconnection will trigger /meta/connect
        assertTrue(connectLatch.await(1000));
        assertEquals(1L, handshakeLatch.jsGet_count());
        assertEquals(1L, failureLatch.jsGet_count());

        evaluateScript("$.cometd.disconnect(true);");
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
