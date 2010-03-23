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
public class CometdSubscribeFailureTest extends AbstractCometdJQueryTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        SubscribeThrowingFilter filter = new SubscribeThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    public void testSubscribeFailure() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("$.cometd.init({url: '" + cometdURL + "', logLevel: 'debug'})");
        assertTrue(readyLatch.await(1000));

        evaluateScript("var subscribeLatch = new Latch(1);");
        Latch subscribeLatch = get("subscribeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        String script = "$.cometd.addListener('/meta/subscribe', subscribeLatch, subscribeLatch.countDown);";
        script += "$.cometd.addListener('/meta/unsuccessful', failureLatch, failureLatch.countDown);";
        evaluateScript(script);

        evaluateScript("$.cometd.subscribe('/echo', subscribeLatch, subscribeLatch.countDown);");
        assertTrue(subscribeLatch.await(1000));
        assertTrue(failureLatch.await(1000));

        // Be sure there is no backoff
        evaluateScript("var backoff = $.cometd.getBackoffPeriod();");
        int backoff = ((Number)get("backoff")).intValue();
        assertEquals(0, backoff);

        evaluateScript("$.cometd.disconnect(true);");
    }

    public static class SubscribeThrowingFilter implements Filter
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
            if (!uri.endsWith("handshake") && !uri.endsWith("connect"))
                throw new IOException();
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
