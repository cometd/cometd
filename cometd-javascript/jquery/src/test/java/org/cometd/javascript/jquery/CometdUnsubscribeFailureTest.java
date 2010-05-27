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
public class CometdUnsubscribeFailureTest extends AbstractCometdJQueryTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        UnsubscribeThrowingFilter filter = new UnsubscribeThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    public void testUnsubscribeFailure() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.addListener('/meta/connect', readyLatch, 'countDown');");
        evaluateScript("$.cometd.init({url: '" + cometdURL + "', logLevel: 'debug'})");
        assertTrue(readyLatch.await(1000));

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = get("subscribeLatch");
        evaluateScript("$.cometd.addListener('/meta/subscribe', subscribeLatch, subscribeLatch.countDown);");
        evaluateScript("var subscription = $.cometd.subscribe('/echo', subscribeLatch, subscribeLatch.countDown);");
        assertTrue(subscribeLatch.await(1000));

        evaluateScript("var unsubscribeLatch = new Latch(1);");
        Latch unsubscribeLatch = get("unsubscribeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("$.cometd.addListener('/meta/unsubscribe', unsubscribeLatch, unsubscribeLatch.countDown);");
        evaluateScript("$.cometd.addListener('/meta/unsuccessful', failureLatch, failureLatch.countDown);");
        evaluateScript("$.cometd.unsubscribe(subscription);");
        assertTrue(unsubscribeLatch.await(1000));
        assertTrue(failureLatch.await(1000));

        // Be sure there is no backoff
        evaluateScript("var backoff = $.cometd.getBackoffPeriod();");
        int backoff = ((Number)get("backoff")).intValue();
        assertEquals(0, backoff);

        evaluateScript("$.cometd.disconnect(true);");
    }

    public static class UnsubscribeThrowingFilter implements Filter
    {
        private int messages;

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
            if (!uri.endsWith("handshake") && !uri.endsWith("connect"))
                ++messages;
            // The second non-handshake and non-connect message will be the unsubscribe, throw
            if (messages == 2)
                throw new IOException();
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
