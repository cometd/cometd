package org.cometd.javascript;

import junit.framework.Assert;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;

public class CometDRedeployTest extends AbstractCometDTest
{
    private ServletContextHandler context;

    @Override
    protected void customizeContext(ServletContextHandler context) throws Exception
    {
        super.customizeContext(context);
        this.context = context;
    }

    @Test
    public void testRedeploy() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("var failureLatch = new Latch(1);");
        Latch failureLatch = get("failureLatch");
        evaluateScript("cometd.addListener('/meta/handshake', handshakeLatch, handshakeLatch.countDown);");
        evaluateScript("cometd.addListener('/meta/connect', function(message)" +
                "{" +
                "   if (message.successful) " +
                "       connectLatch.countDown();" +
                "   else" +
                "       failureLatch.countDown();" +
                "});");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(1000));
        Assert.assertTrue(connectLatch.await(1000));

        // Wait for the second connect to reach the server
        Thread.sleep(500);

        // Redeploy the context
        handshakeLatch.reset(1);
        connectLatch.reset(1);
        context.stop();
        Assert.assertTrue(failureLatch.await(1000));
        // Assume the redeploy takes a while
        long backoffIncrement = ((Number)evaluateScript("cometd.getBackoffIncrement();")).longValue();
        Thread.sleep(2 * backoffIncrement);
        // Restart the context
        context.start();

        long backoffPeriod = ((Number)evaluateScript("cometd.getBackoffPeriod();")).longValue();
        Assert.assertTrue(handshakeLatch.await(backoffPeriod + 2 * backoffIncrement));
        Assert.assertTrue(connectLatch.await(1000));

        evaluateScript("cometd.disconnect(true);");
    }
}
