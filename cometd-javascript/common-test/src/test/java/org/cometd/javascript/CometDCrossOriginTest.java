package org.cometd.javascript;

import junit.framework.Assert;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.junit.Test;

public class CometDCrossOriginTest extends AbstractCometDTest
{
    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        CrossOriginFilter filter = new CrossOriginFilter();
        FilterHolder filterHolder = new FilterHolder(filter);
        context.addFilter(filterHolder, cometServletPath + "/*", FilterMapping.REQUEST);
    }

    @Test
    public void testCrossOriginSupported() throws Exception
    {
        defineClass(Latch.class);
        String crossOriginCometdURL = cometdURL.replace("localhost", "127.0.0.1");
        evaluateScript("cometd.configure({" +
                       "url: '" + crossOriginCometdURL + "', " +
                       "requestHeaders: { Origin: 'http://localhost:8080' }, " +
                       "logLevel: 'debug'" +
                       "});");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { if (message.successful) connectLatch.countDown(); });");
        evaluateScript("cometd.handshake();");

        Assert.assertTrue(connectLatch.await(1000));
        Assert.assertEquals("long-polling", evaluateScript("cometd.getTransport().getType()"));

        evaluateScript("cometd.disconnect(true);");
    }
}
