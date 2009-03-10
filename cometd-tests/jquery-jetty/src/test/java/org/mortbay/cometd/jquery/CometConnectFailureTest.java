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
public class CometConnectFailureTest extends CometTest
{
    @Override
    protected void customizeContext(Context context)
    {
        ConnectThrowingFilter filter = new ConnectThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", Handler.REQUEST);
    }

    @Test
    public void testConnectFailure() throws Exception
    {
        evaluateScript("$.cometd.setLogLevel('debug');");
        defineClass(Listener.class);
        evaluateScript("var handshakeListener = new Listener();");
        Listener handshakeListener = get("handshakeListener");
        evaluateScript("var failureListener = new Listener();");
        Listener failureListener = get("failureListener");
        evaluateScript("var connectListener = new Listener();");
        Listener connectListener = get("connectListener");
        String script = "$.cometd.addListener('/meta/handshake', handshakeListener, handshakeListener.handle);";
        script += "$.cometd.addListener('/meta/unsuccessful', failureListener, failureListener.handle);";
        script += "$.cometd.addListener('/meta/connect', connectListener, connectListener.handle);";

        script += "var backoff = $.cometd.getBackoffPeriod();";
        script += "var backoffIncrement = $.cometd.getBackoffIncrement();";
        evaluateScript(script);
        int backoff = ((Number)get("backoff")).intValue();
        final int backoffIncrement = ((Number)get("backoffIncrement")).intValue();
        assert backoff == 0;
        assert backoffIncrement > 0;

        handshakeListener.jsFunction_expect(1);
        connectListener.jsFunction_expect(1);
        failureListener.jsFunction_expect(1);
        evaluateScript("$.cometd.init('" + cometURL + "')");
        assert handshakeListener.await(1000);
        assert connectListener.await(1000);
        assert failureListener.await(1000);

        // There is a failure, the backoff will be increased from 0 to backoffIncrement
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = $.cometd.getBackoffPeriod();");
        backoff = ((Number)get("backoff")).intValue();
        assert backoff == backoffIncrement : backoff;

        connectListener.jsFunction_expect(1);
        failureListener.jsFunction_expect(1);
        assert connectListener.await(backoffIncrement);
        assert failureListener.await(backoffIncrement);

        // Another failure, backoff will be increased to 2 * backoffIncrement
        Thread.sleep(backoffIncrement / 2); // Waits for the backoff to happen
        evaluateScript("var backoff = $.cometd.getBackoffPeriod();");
        backoff = ((Number)get("backoff")).intValue();
        assert backoff == 2 * backoffIncrement : backoff;

        connectListener.jsFunction_expect(1);
        failureListener.jsFunction_expect(1);
        assert connectListener.await(2 * backoffIncrement);
        assert failureListener.await(2 * backoffIncrement);

        // Disconnect so that connect is not performed anymore
        evaluateScript("var disconnectListener = new Listener();");
        Listener disconnectListener = get("disconnectListener");
        disconnectListener.jsFunction_expect(1);
        failureListener.jsFunction_expect(1);
        script = "$.cometd.addListener('/meta/disconnect', disconnectListener, disconnectListener.handle);";
        script += "$.cometd.disconnect();";
        evaluateScript(script);
        assert disconnectListener.await(1000);
        assert failureListener.await(1000);
        String status = evaluateScript("$.cometd.getStatus();");
        assert "disconnected".equals(status) : status;

        // Be sure the connect is not retried anymore
        connectListener.jsFunction_expect(1);
        assert !connectListener.await(4 * backoffIncrement);
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

    public static class ConnectThrowingFilter implements Filter
    {
        private boolean handshook;

        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            doFilter((HttpServletRequest)request, (HttpServletResponse)response, chain);
        }

        private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            if (handshook) throw new IOException();
            handshook = true;
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
