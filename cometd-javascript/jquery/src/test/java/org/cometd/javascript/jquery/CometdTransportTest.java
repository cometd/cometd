package org.cometd.javascript.jquery;

import java.util.Arrays;

import org.cometd.javascript.Latch;

/**
 * @version $Revision$ $Date$
 */
public class CometdTransportTest extends AbstractCometdJQueryTest
{
    public void testTransport() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        Object[] transportTypes = (Object[])jsToJava(evaluateScript("$.cometd.getTransportTypes()"));
        // The spec requires at least these 2 transports
        assertTrue(Arrays.asList(transportTypes).contains("long-polling"));
        assertTrue(Arrays.asList(transportTypes).contains("callback-polling"));

        // Remove all transports
        evaluateScript("" +
                "var types = $.cometd.getTransportTypes();" +
                "for (var i = 0; i < types.length; ++i)" +
                "{" +
                "   $.cometd.unregisterTransport(types[i]);" +
                "};");

        evaluateScript("" +
                "var readyLatch = new Latch(1);" +
                "function LocalTransport()" +
                "{" +
                "    this.sends = 0;" +
                "" +
                "    this.accept = function(version, crossDomain)" +
                "    {" +
                "        return true;" +
                "    };" +
                "" +
                "    this.transportSend = function(envelope, request)" +
                "    {" +
                "        ++this.sends;" +
                "        var response;" +
                "        var timeout;" +
                "        switch (this.sends)" +
                "        {" +
                "            case 1:" +
                "                response = '[{" +
                "                    \"successful\":true," +
                "                    \"channel\":\"/meta/handshake\"," +
                "                    \"clientId\":\"dmigjcjnakuysa9j29\"," +
                "                    \"id\":\"1\"," +
                "                    \"minimumVersion\":\"0.9\"," +
                "                    \"version\":\"1.0\"," +
                "                    \"supportedConnectionTypes\":[\"long-polling\",\"callback-polling\"]," +
                "                    \"advice\":{\"reconnect\":\"retry\",\"interval\":0,\"timeout\":5000}" +
                "                }]';" +
                "                timeout = 0;" +
                "                break;" +
                "            case 2:" +
                "                response = '[{" +
                "                    \"successful\":true," +
                "                    \"channel\":\"/meta/connect\"," +
                "                    \"id\":\"2\"," +
                "                    \"advice\":{\"reconnect\":\"retry\",\"interval\":0,\"timeout\":5000}" +
                "                }]';" +
                "                timeout = 0;" +
                "                break;" +
                "            case 3:" +
                "                response = '[{" +
                "                    \"successful\":true," +
                "                    \"channel\":\"/meta/connect\"," +
                "                    \"id\":\"2\"," +
                "                    \"advice\":{\"reconnect\":\"retry\",\"interval\":0,\"timeout\":5000}" +
                "                }]';" +
                "                timeout = 5000;" +
                "                readyLatch.countDown();" +
                "                break;" +
                "            case 4:" +
                "                response = '[{" +
                "                    \"successful\":true," +
                "                    \"channel\":\"/meta/disconnect\"," +
                "                    \"id\":\"3\"" +
                "                }]';" +
                "                timeout = 0;" +
                "                break;" +
                "            default:" +
                "                throw 'Test Error';" +
                "        }" +
                "" +
                "        /* Respond asynchronously */" +
                "        setTimeout(function()" +
                "        {" +
                "            envelope.onSuccess(request, response);" +
                "        }, timeout);" +
                "    };" +
                "};" +
                "LocalTransport.prototype = new org.cometd.Transport();" +
                "LocalTransport.prototype.constructor = LocalTransport;" +
                "");

        evaluateScript("var localTransport = new LocalTransport();");
        // The server does not support a 'local' transport, so use 'long-polling'
        assertTrue((Boolean)evaluateScript("$.cometd.registerTransport('long-polling', localTransport);"));

        Latch readyLatch = get("readyLatch");
        evaluateScript("$.cometd.handshake();");
        assertTrue(readyLatch.await(1000));

        assertEquals(3, ((Number)evaluateScript("localTransport.sends;")).intValue());
        assertEquals("connected", evaluateScript("$.cometd.getStatus();"));

        readyLatch.reset(1);
        evaluateScript("$.cometd.addListener('/meta/disconnect', readyLatch, 'countDown');");
        evaluateScript("$.cometd.disconnect();");
        assertTrue(readyLatch.await(1000));

        assertEquals(4, ((Number)evaluateScript("localTransport.sends;")).intValue());
        assertEquals("disconnected", evaluateScript("$.cometd.getStatus();"));
    }
}
