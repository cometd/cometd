package org.cometd.javascript.dojo;

import org.cometd.javascript.Latch;

/**
 * @version $Revision$ $Date$
 */
public class CometdDojoHitchTest extends AbstractCometdDojoTest
{
    public void testDojoHitch() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("dojox.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        evaluateScript("var handshakeLatch = new Latch(1);");
        Latch handshakeLatch = get("handshakeLatch");
        evaluateScript("dojox.cometd.addListener('/meta/handshake', handshakeLatch, 'countDown');");
        evaluateScript("dojox.cometd.handshake();");
        assertTrue(handshakeLatch.await(1000));

        evaluateScript("var latch1 = new Latch(1);");
        Latch latch1 = get("latch1");
        evaluateScript("dojox.cometd.subscribe('/test', dojo.hitch(latch1, 'countDown'), {});");
        evaluateScript("var latch2 = new Latch(1);");
        Latch latch2 = get("latch2");
        evaluateScript("dojox.cometd.subscribe('/test', dojo.hitch(latch2, 'countDown'));");
        evaluateScript("dojox.cometd.publish('/test', {});");
        assertTrue(latch1.await(1000));
        assertTrue(latch2.await(1000));

        evaluateScript("dojox.cometd.disconnect(true);");
    }
}
