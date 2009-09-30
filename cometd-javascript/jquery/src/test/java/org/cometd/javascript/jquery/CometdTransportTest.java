package org.cometd.javascript.jquery;

/**
 * @version $Revision$ $Date$
 */
public class CometdTransportTest extends AbstractCometdJQueryTest
{
    public void testTransport() throws Exception
    {
        evaluateScript("$.cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        Object[] transportTypes = (Object[])jsToJava(evaluateScript("$.cometd.getTransportTypes()"));
        assertEquals("long-polling", transportTypes[0]);
        assertEquals("callback-polling", transportTypes[1]);

        evaluateScript("var originalTransport = $.cometd.unregisterTransport('long-polling');");
        assertNotNull(get("originalTransport"));

        String localTransport =
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
                "LocalTransport.prototype.constructor = LocalTransport;";

        evaluateScript(localTransport);

        evaluateScript("var localTransport = new LocalTransport();");
        assertTrue((Boolean)evaluateScript("$.cometd.registerTransport('long-polling', localTransport);"));

        evaluateScript("$.cometd.handshake();");
        Thread.sleep(500);
        assertEquals(3, ((Number)evaluateScript("localTransport.sends;")).intValue());
        assertEquals("connected", evaluateScript("$.cometd.getStatus();"));
        evaluateScript("$.cometd.disconnect();");
        assertEquals(4, ((Number)evaluateScript("localTransport.sends;")).intValue());
        Thread.sleep(500);
        assertEquals("disconnected", evaluateScript("$.cometd.getStatus();"));
    }
}
