package org.mortbay.cometd.jquery;

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

import org.mortbay.jetty.Handler;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.FilterHolder;
import org.mozilla.javascript.ScriptableObject;
import org.testng.annotations.Test;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometSubscribeFailureTest extends CometTest
{
    @Override
    protected void customizeContext(Context context)
    {
        SubscribeThrowingFilter filter = new SubscribeThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", Handler.REQUEST);
    }

    @Test
    public void testSubscribeFailure() throws Exception
    {
        evaluateScript("$.cometd.setLogLevel('debug');");
        defineClass(Listener.class);
        evaluateScript("$.cometd.init('" + cometURL + "')");

        // Wait for the long poll
        Thread.sleep(1000);

        evaluateScript("var subscribeListener = new Listener();");
        Listener subscribeListener = get("subscribeListener");
        evaluateScript("var failureListener = new Listener();");
        Listener failureListener = get("failureListener");
        String script = "$.cometd.addListener('/meta/subscribe', subscribeListener, subscribeListener.handle);";
        script += "$.cometd.addListener('/meta/unsuccessful', failureListener, failureListener.handle);";
        evaluateScript(script);

        subscribeListener.jsFunction_expect(1);
        failureListener.jsFunction_expect(1);
        evaluateScript("$.cometd.subscribe('/echo', subscribeListener, subscribeListener.handle);");
        assert subscribeListener.await(1000);
        assert failureListener.await(1000);

        // Be sure there is no backoff
        evaluateScript("var backoff = $.cometd.getBackoffPeriod();");
        int backoff = ((Number)get("backoff")).intValue();
        assert backoff == 0;

        evaluateScript("var disconnectListener = new Listener();");
        Listener disconnectListener = get("disconnectListener");
        disconnectListener.jsFunction_expect(1);
        script = "$.cometd.addListener('/meta/disconnect', disconnectListener, disconnectListener.handle);";
        script += "$.cometd.disconnect();";
        evaluateScript(script);
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

    public static class SubscribeThrowingFilter implements Filter
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
            // The fourth message will be the subscribe, throw
            if (messages == 4) throw new IOException();
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
