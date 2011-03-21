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

/**
 * Tests that failing the disconnect, the comet communication is aborted anyway
 */
public class CometDDisconnectFailureTest extends AbstractCometDTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        DisconnectThrowingFilter filter = new DisconnectThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    @Test
    public void testDisconnectFailure() throws Exception
    {
        defineClass(Latch.class);

        evaluateScript("var readyLatch = new Latch(1);");
        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { readyLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "', logLevel: 'debug'})");
        Assert.assertTrue(readyLatch.await(1000));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("cometd.addListener('/meta/disconnect', disconnectLatch, disconnectLatch.countDown);");

        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(1000));

        // The test ends here, as we cannot get any information about the fact that
        // the long poll call returned (which we would have liked to, confirming that
        // a client-side only disconnect() actually stops the comet communication,
        // even if it fails).
        // The XmlHttpRequest specification says that if the response has not begun,
        // then aborting the XmlHttpRequest calling xhr.abort() does not result in
        // any notification. The network activity is stopped, but no notification is
        // emitted by calling onreadystatechange(). Therefore, comet cannot call any
        // callback to signal this, and any connect listener will not be notified.
    }

    public static class DisconnectThrowingFilter implements Filter
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
            if (uri.endsWith("disconnect"))
                throw new IOException();
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
