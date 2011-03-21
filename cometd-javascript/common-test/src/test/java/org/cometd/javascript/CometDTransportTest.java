package org.cometd.javascript;

import java.util.Arrays;

import junit.framework.Assert;
import org.junit.Test;

public class CometDTransportTest extends AbstractCometDTest
{
    @Test
    public void testTransport() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("cometd.configure({url: '" + cometdURL + "', logLevel: 'debug'});");
        Object[] transportTypes = (Object[])Utils.jsToJava(evaluateScript("cometd.getTransportTypes()"));
        // The spec requires at least these 2 transports
        Assert.assertTrue(Arrays.asList(transportTypes).contains("long-polling"));
        Assert.assertTrue(Arrays.asList(transportTypes).contains("callback-polling"));

        // Remove all transports
        evaluateScript("" +
                "var types = cometd.getTransportTypes();" +
                "for (var i = 0; i < types.length; ++i)" +
                "{" +
                "   cometd.unregisterTransport(types[i]);" +
                "};");

        String localTransport =
                "var readyLatch = new Latch(1);" +
                "function LocalTransport()" +
                "{" +
                "    var _super = new org.cometd.RequestTransport();" +
                "    var that = org.cometd.Transport.derive(_super);" +
                "    var _sends = 0;" +
                "" +
                "    that.getSends = function() { return _sends; };" +
                "" +
                "    that.accept = function(version, crossDomain)" +
                "    {" +
                "        return true;" +
                "    };" +
                "" +
                "    that.transportSend = function(envelope, request)" +
                "    {" +
                "        ++_sends;" +
                "        var response;" +
                "        var timeout;" +
                "        switch (_sends)" +
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
                "        var self = this;" +
                "        setTimeout(function()" +
                "        {" +
                "            self.transportSuccess(envelope, request, self.convertToMessages(response));" +
                "        }, timeout);" +
                "    };" +
                "" +
                "    return that;" +
                "};";

        evaluateScript(localTransport);

        evaluateScript("var localTransport = new LocalTransport();");
        // The server does not support a 'local' transport, so use 'long-polling'
        Assert.assertTrue((Boolean)evaluateScript("cometd.registerTransport('long-polling', localTransport);"));

        Latch readyLatch = get("readyLatch");
        evaluateScript("cometd.handshake();");
        Assert.assertTrue(readyLatch.await(1000));

        Assert.assertEquals(3, ((Number)evaluateScript("localTransport.getSends();")).intValue());
        Assert.assertEquals("connected", evaluateScript("cometd.getStatus();"));

        readyLatch.reset(1);
        evaluateScript("cometd.addListener('/meta/disconnect', readyLatch, 'countDown');");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(readyLatch.await(1000));

        Assert.assertEquals(4, ((Number)evaluateScript("localTransport.getSends();")).intValue());
        Assert.assertEquals("disconnected", evaluateScript("cometd.getStatus();"));
    }
}
