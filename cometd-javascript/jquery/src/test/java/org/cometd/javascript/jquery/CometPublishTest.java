package org.cometd.javascript.jquery;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;
import org.testng.annotations.Test;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometPublishTest extends AbstractJQueryCometTest
{
    @Test
    public void testPublish() throws Exception
    {
        defineClass(Listener.class);
        evaluateScript("$.cometd.init({url: '" + cometURL + "', logLevel: 'debug'})");

        // Wait for the long poll
        Thread.sleep(1000);

        evaluateScript("var echoListener = new Listener();");
        Listener echoListener = get("echoListener");
        evaluateScript("var subscription = $.cometd.subscribe('/echo', echoListener, echoListener.handle);");
        evaluateScript("var publishListener = new Listener();");
        Listener publishListener = get("publishListener");
        evaluateScript("$.cometd.addListener('/meta/publish', publishListener, publishListener.handle);");

        echoListener.jsFunction_expect(1);
        publishListener.jsFunction_expect(1);
        evaluateScript("$.cometd.publish('/echo', 'test');");
        assert echoListener.await(1000);
        assert publishListener.await(1000);

        evaluateScript("var disconnectListener = new Listener();");
        Listener disconnectListener = get("disconnectListener");
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
}
