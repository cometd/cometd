package org.cometd.javascript;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.mozilla.javascript.ScriptableObject;

public class CometDMaxNetworkDelayLongPollTest extends AbstractCometDTest
{
    private final long maxNetworkDelay = 2000;

    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        DelayingFilter filter = new DelayingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    @Test
    public void testMaxNetworkDelay() throws Exception
    {
        defineClass(Listener.class);
        evaluateScript("var connectListener = new Listener();");
        Listener connectListener = get("connectListener");
        evaluateScript("cometd.addListener('/meta/connect', connectListener, connectListener.handle);");
        evaluateScript("cometd.configure({" +
                       "url: '" + cometdURL + "', " +
                       "maxNetworkDelay: " + maxNetworkDelay + ", " +
                       "logLevel: 'debug'" +
                       "});");

        AtomicReference<List<Throwable>> failures = new AtomicReference<List<Throwable>>(new ArrayList<Throwable>());
        connectListener.expect(failures, 2);
        evaluateScript("cometd.handshake();");

        // The long poll is supposed to return within longPollPeriod.
        // However, the test holds it for longPollPeriod + 2 * maxNetworkDelay
        // The request timeout kicks in after longPollPeriod + maxNetworkDelay,
        // canceling the request.
        Assert.assertTrue(connectListener.await(longPollingPeriod + 2 * maxNetworkDelay));
        Assert.assertTrue(failures.get().toString(), failures.get().isEmpty());

        evaluateScript("cometd.disconnect(true);");
    }

    public static class Listener extends ScriptableObject
    {
        private AtomicReference<List<Throwable>> failures;
        private CountDownLatch latch;

        public String getClassName()
        {
            return "Listener";
        }

        public void jsFunction_handle(Object jsMessage)
        {
            Map<String, Object> message = (Map<String, Object>)Utils.jsToJava(jsMessage);
            if (latch.getCount() == 2)
            {
                // First connect must be ok
                if (!(Boolean)message.get("successful"))
                    failures.get().add(new AssertionError("First Connect"));
            }
            else if (latch.getCount() == 1)
            {
                // Second connect must fail
                if ((Boolean)message.get("successful"))
                    failures.get().add(new AssertionError("Second Connect"));
            }
            latch.countDown();
        }

        public void expect(AtomicReference<List<Throwable>> failures, int count)
        {
            this.failures = failures;
            this.latch = new CountDownLatch(count);
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }
    }

    private class DelayingFilter implements Filter
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
            // We hold the second connect longer than the long poll timeout + maxNetworkDelay
            if (connects == 2)
            {
                try
                {
                    Thread.sleep(longPollingPeriod + 2 * maxNetworkDelay);
                }
                catch (InterruptedException x)
                {
                    throw new IOException();
                }
            }
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
