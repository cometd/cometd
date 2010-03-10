package org.cometd.javascript.jquery;

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

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdMultiPublishTest extends AbstractCometdJQueryTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        PublishThrowingFilter filter = new PublishThrowingFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    public void testMultiPublish() throws Throwable
    {
        evaluateScript("$.cometd.init({url: '" + cometdURL + "', logLevel: 'debug'});");

        // Wait for the long poll
        Thread.sleep(1000);

        defineClass(Listener.class);
        evaluateScript("var listener = new Listener();");
        Listener listener = get("listener");
        evaluateScript("$.cometd.subscribe('/echo', listener, listener.listen);");
        // Wait for subscribe to return
        Thread.sleep(1000);

        defineClass(Handler.class);
        evaluateScript("var handler = new Handler();");
        Handler handler = get("handler");
        evaluateScript("$.cometd.addListener('/meta/publish', handler, handler.handle);");
        evaluateScript("var disconnect = new Listener();");
        Listener disconnect = get("disconnect");
        evaluateScript("$.cometd.addListener('/meta/disconnect', disconnect, disconnect.listen);");

        listener.expect(1);
        AtomicReference<List<Throwable>> failures = new AtomicReference<List<Throwable>>(new ArrayList<Throwable>());
        handler.expect(failures, 4);
        disconnect.expect(1);

        // These publish are sent without waiting each one to return,
        // so they will be queued. The second publish will fail, we
        // expect the following to fail as well, in order.
        evaluateScript("$.cometd.publish('/echo', {id: 1});" +
                "$.cometd.publish('/echo', {id: 2});" +
                "$.cometd.publish('/echo', {id: 3});" +
                "$.cometd.publish('/echo', {id: 4});" +
                "$.cometd.disconnect();");

        assertTrue(listener.await(1000));
        assertTrue(handler.await(1000));
        assertTrue(failures.get().toString(), failures.get().isEmpty());
        assertTrue(disconnect.await(1000));
    }

    public static class Listener extends ScriptableObject
    {
        private CountDownLatch latch;

        public String getClassName()
        {
            return "Listener";
        }

        public void jsFunction_listen(Object message)
        {
            latch.countDown();
        }

        public void expect(int count)
        {
            latch = new CountDownLatch(count);
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }
    }

    public static class Handler extends ScriptableObject
    {
        private int id;
        private AtomicReference<List<Throwable>> failures;
        private CountDownLatch latch;

        public String getClassName()
        {
            return "Handler";
        }

        public void jsFunction_handle(Object jsMessage)
        {
            Map message = (Map)jsToJava(jsMessage);
            Boolean successful = (Boolean)message.get("successful");
            ++id;
            if (id == 1)
            {
                // First publish should succeed
                if (successful == null || !successful) failures.get().add(new AssertionError("Publish " + id + " expected successful"));
            }
            else if (id == 2 || id == 3 || id == 4)
            {
                // Second publish should fail because of the server
                // Third and fourth are soft failed by the comet implementation
                if (successful == null || successful)
                {
                    failures.get().add(new AssertionError("Publish " + id + " expected unsuccessful"));
                }
                else
                {
                    Map data = (Map)((Map)message.get("request")).get("data");
                    int dataId = ((Number)data.get("id")).intValue();
                    if (dataId != id) failures.get().add(new AssertionError("data id " + dataId + ", expecting " + id));
                }
            }
            latch.countDown();
        }

        public void expect(AtomicReference<List<Throwable>> failures, int count)
        {
            this.failures = failures;
            latch = new CountDownLatch(count);
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }
    }

    public static class PublishThrowingFilter implements Filter
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
            // The sixth message will be the second publish, throw
            if (messages == 6) throw new IOException();
            chain.doFilter(request, response);
        }

        public void destroy()
        {
        }
    }
}
