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

public class CometDEmptyResponseTest extends AbstractCometDTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        EmptyResponseFilter filter = new EmptyResponseFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    @Test
    public void testEmptyResponse() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("cometd.addListener('/meta/handshake', handshakeLatch, 'countDown');");
        evaluateScript("cometd.addListener('/meta/unsuccessful', failureLatch, 'countDown');");

        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(1000));
        Assert.assertTrue(failureLatch.await(1000));

        evaluateScript("cometd.disconnect(true);");
    }

    public static class EmptyResponseFilter implements Filter
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
            response.setContentType("application/json;charset=UTF-8");
        }

        public void destroy()
        {
        }
    }
}
