package org.cometd.javascript.jquery;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.ScriptableObject;

/**
 * @version $Revision$ $Date$
 */
public class CometdPublishHeldUntilConnectedTest extends AbstractCometdJQueryTest
{
    public void testPublishHeldUntilConnected() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        StringBuilder script = new StringBuilder();
        script.append("var latch = new Latch(1);");
        script.append("var savedChannels;");
        script.append("var channels = [];");
        script.append("$.cometd.registerExtension('test', {");
        script.append("    outgoing: function(message) ");
        script.append("    {");
        script.append("        channels.push(message.channel);");
        script.append("    }");
        script.append("});");
        script.append("$.cometd.addListener('/meta/handshake', function(message)");
        script.append("{");
        script.append("    $.cometd.publish('/bar', {});");
        script.append("    $.cometd.batch(function()");
        script.append("    {");
        script.append("        $.cometd.subscribe('/foo', function(msg)");
        script.append("        {");
        script.append("            /* Copy the array so that from now on it is not modified anymore */");
        script.append("            savedChannels = channels.slice(0);");
        script.append("            latch.countDown();");
        script.append("        });");
        script.append("        $.cometd.publish('/foo', {});");
        script.append("    });");
        script.append("});");
        evaluateScript(script.toString());
        Latch latch = (Latch)get("latch");
        evaluateScript("$.cometd.handshake();");

        assertTrue(latch.await(2000));

        Object jsChannels = get("savedChannels");
        Object[] channels = (Object[])jsToJava(jsChannels);
        assertNotNull(channels);
        List<Object> expectedChannels = Arrays.<Object>asList("/meta/handshake", "/meta/connect", "/bar", "/meta/subscribe", "/foo", "/meta/connect");
        assertEquals(expectedChannels, Arrays.asList(channels));

        evaluateScript("$.cometd.disconnect();");
        Thread.sleep(500); // Wait for the disconnect to return
    }

    public static class Latch extends ScriptableObject
    {
        private volatile CountDownLatch latch;

        public String getClassName()
        {
            return "Latch";
        }

        public void jsConstructor(int count)
        {
            latch = new CountDownLatch(count);
        }

        public boolean await(long timeout) throws InterruptedException
        {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        }

        public void jsFunction_countDown()
        {
            latch.countDown();
        }
    }
}
