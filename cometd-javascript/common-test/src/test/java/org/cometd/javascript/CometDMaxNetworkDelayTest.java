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

public class CometDMaxNetworkDelayTest extends AbstractCometDTest
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
        evaluateScript("var publishListener = new Listener();");
        Listener publishListener = get("publishListener");
        evaluateScript("cometd.addListener('/meta/publish', publishListener, publishListener.handle);");
        evaluateScript("cometd.configure({" +
                       "url: '" + cometdURL + "', " +
                       "maxNetworkDelay: " + maxNetworkDelay + ", " +
                       "logLevel: 'debug'" +
                       "});");

        evaluateScript("cometd.handshake();");

        Thread.sleep(500); // Allow long poll to establish

        AtomicReference<List<Throwable>> failures = new AtomicReference<List<Throwable>>(new ArrayList<Throwable>());
        publishListener.expect(failures, 1);
        evaluateScript("cometd.publish('/test', {});");

        // The publish() above is supposed to return immediately
        // However, the test holds it for 2 * maxNetworkDelay
        // The request timeout kicks in after maxNetworkDelay,
        // canceling the request.
        Assert.assertTrue(publishListener.await(2 * maxNetworkDelay));
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
            if ((Boolean)message.get("successful"))
                failures.get().add(new AssertionError("Publish"));
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
            {
                // We hold the publish longer than the maxNetworkDelay
                try
                {
                    Thread.sleep(2 * maxNetworkDelay);
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
