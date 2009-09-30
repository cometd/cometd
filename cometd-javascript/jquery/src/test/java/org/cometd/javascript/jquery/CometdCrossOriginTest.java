package org.cometd.javascript.jquery;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision$ $Date$
 */
public class CometdCrossOriginTest extends AbstractCometdJQueryTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        CrossOriginFilter filter = new CrossOriginFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    public void testCrossOriginSupported() throws Exception
    {
        String crossOriginCometdURL = cometURL.replace("localhost", "127.0.0.1");
        evaluateScript("$.cometd.configure({" +
                       "url: '" + crossOriginCometdURL + "', " +
                       "requestHeaders: { Origin: 'http://localhost:8080' }, " +
                       "logLevel: 'debug'" +
                       "});");
        defineClass(Listener.class);
        evaluateScript("var connectListener = new Listener();");
        Listener connectListener = get("connectListener");
        evaluateScript("$.cometd.addListener('/meta/connect', function(message) { if (message.successful) connectListener.countDown(); });");
        connectListener.expect(1);
        evaluateScript("$.cometd.handshake();");

        assert connectListener.await(1000);
        assert "long-polling".equals(evaluateScript("$.cometd.getTransport().getType()"));
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

        public void jsFunction_countDown()
        {
            if (latch.getCount() == 0) throw new AssertionError();
            latch.countDown();
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }
    }
}
