package org.cometd.javascript.jquery;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.mozilla.javascript.ScriptableObject;
import org.testng.annotations.Test;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometUnsubscribeFailureTest extends AbstractJQueryCometTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        UnsubscribeThrowingFilter filter = new UnsubscribeThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    @Test
    public void testUnsubscribeFailure() throws Exception
    {
        evaluateScript("$.cometd.setLogLevel('debug');");
        defineClass(Listener.class);
        evaluateScript("$.cometd.init('" + cometURL + "')");

        // Wait for the long poll
        Thread.sleep(1000);

        evaluateScript("var subscribeListener = new Listener();");
        Listener subscribeListener = get("subscribeListener");
        evaluateScript("$.cometd.addListener('/meta/subscribe', subscribeListener, subscribeListener.handle);");
        subscribeListener.jsFunction_expect(1);
        evaluateScript("var subscription = $.cometd.subscribe('/echo', subscribeListener, subscribeListener.handle);");
        assert subscribeListener.await(1000);

        evaluateScript("var unsubscribeListener = new Listener();");
        Listener unsubscribeListener = get("unsubscribeListener");
        evaluateScript("var failureListener = new Listener();");
        Listener failureListener = get("failureListener");
        evaluateScript("$.cometd.addListener('/meta/unsubscribe', unsubscribeListener, unsubscribeListener.handle);");
        evaluateScript("$.cometd.addListener('/meta/unsuccessful', failureListener, failureListener.handle);");
        unsubscribeListener.jsFunction_expect(1);
        failureListener.jsFunction_expect(1);
        evaluateScript("$.cometd.unsubscribe(subscription);");
        assert unsubscribeListener.await(1000);
        assert failureListener.await(1000);

        // Be sure there is no backoff
        evaluateScript("var backoff = $.cometd.getBackoffPeriod();");
        int backoff = ((Number)get("backoff")).intValue();
        assert backoff == 0;

        evaluateScript("var disconnectListener = new Listener();");
        Listener disconnectListener = (Listener)get("disconnectListener");
        disconnectListener.jsFunction_expect(1);
        evaluateScript("$.cometd.addListener('/meta/disconnect', disconnectListener, disconnectListener.handle);");
        evaluateScript("$.cometd.disconnect();");
        assert disconnectListener.await(1000);
        String status = evaluateScript("$.cometd.getStatus();");
        assert "disconnected".equals(status) : status;
    }

    public static class Listener extends ScriptableObject
    {
        private CountDownLatch latch;

        public void jsFunction_expect(int messageCount)
        {
            latch = new CountDownLatch(messageCount);
        }

        public String getClassName()
        {
            return "Listener";
        }

        public void jsFunction_handle(Object message)
        {
            if (latch.getCount() == 0) throw new AssertionError();
            latch.countDown();
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }
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
            ++messages;
            // The fifth message will be the unsubscribe, throw
            if (messages == 5) throw new IOException();
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
