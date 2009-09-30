package org.cometd.javascript.jquery;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision: 1453 $ $Date: 2009-02-25 12:57:20 +0100 (Wed, 25 Feb 2009) $
 */
public class CometdBatchPublishTest extends AbstractCometdJQueryTest
{
    public void testBatchPublish() throws Exception
    {
        evaluateScript("$.cometd.configure({url: '" + cometURL + "', logLevel: 'debug'});");
        defineClass(Listener.class);
        StringBuilder script = new StringBuilder();
        script.append("var listener = new Listener();");
        script.append("var _connected;");
        script.append("function connected(message)");
        script.append("{ ");
        script.append("    var wasConnected = _connected;");
        script.append("    _connected = message.successful;");
        script.append("    if (!wasConnected && connected)");
        script.append("    {");
        script.append("        $.cometd.startBatch();");
        script.append("        $.cometd.subscribe('/echo', listener, listener.handle); ");
        script.append("        $.cometd.publish('/echo', 'test'); ");
        script.append("        $.cometd.endBatch(); ");
        script.append("    }");
        script.append("}");
        script.append("$.cometd.addListener('/meta/connect', this, connected);");
        evaluateScript(script.toString());
        Listener listener = get("listener");

        listener.jsFunction_expect(1);
        evaluateScript("$.cometd.handshake();");
        assertTrue(listener.await(1000));

        evaluateScript("var disconnectListener = new Listener();");
        Listener disconnectListener = get("disconnectListener");
        disconnectListener.jsFunction_expect(1);
        evaluateScript("$.cometd.addListener('/meta/disconnect', disconnectListener, disconnectListener.handle);");
        evaluateScript("$.cometd.disconnect();");
        assertTrue(disconnectListener.await(1000));
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
