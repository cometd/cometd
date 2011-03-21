package org.cometd.javascript.dojo;

import junit.framework.Assert;
import org.cometd.javascript.AbstractCometDTest;
import org.cometd.javascript.Latch;
import org.junit.Test;

public class CometDDojoHitchTest extends AbstractCometDTest
{
    @Test
    public void testDojoHitch() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("cometd.addListener('/meta/handshake', handshakeLatch, 'countDown');");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(handshakeLatch.await(1000));

        evaluateScript("var latch1 = new Latch(1);");
        Latch latch1 = get("latch1");
        evaluateScript("cometd.subscribe('/test', dojo.hitch(latch1, 'countDown'), {});");
        evaluateScript("var latch2 = new Latch(1);");
        Latch latch2 = get("latch2");
        evaluateScript("cometd.subscribe('/test', dojo.hitch(latch2, 'countDown'));");
        evaluateScript("cometd.publish('/test', {});");
        Assert.assertTrue(latch1.await(1000));
        Assert.assertTrue(latch2.await(1000));

        evaluateScript("cometd.disconnect(true);");
    }
}
