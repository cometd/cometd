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

/**
 * Tests that failing the disconnect, the comet communication is aborted anyway
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdDisconnectFailureTest extends AbstractCometdJQueryTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        DisconnectThrowingFilter filter = new DisconnectThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    public void testDisconnectFailure() throws Exception
    {
        defineClass(Listener.class);
        evaluateScript("$.cometd.init({url: '" + cometURL + "', logLevel: 'debug'})");

        // Wait for the long poll
        Thread.sleep(1000);

        evaluateScript("var disconnectListener = new Listener();");
        Listener disconnectListener = get("disconnectListener");
        evaluateScript("$.cometd.addListener('/meta/disconnect', disconnectListener, disconnectListener.handle);");

        disconnectListener.expect(1);
        evaluateScript("$.cometd.disconnect();");
        assertTrue(disconnectListener.await(1000));

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

    public static class Listener extends ScriptableObject
    {
        private CountDownLatch latch;

        public String getClassName()
        {
            return "Listener";
        }

        public void expect(int messageCount)
        {
            latch = new CountDownLatch(messageCount);
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

    public static class DisconnectThrowingFilter implements Filter
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
            // The fourth message will be the disconnect, throw
            if (messages == 4) throw new IOException();
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
