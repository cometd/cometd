package org.cometd.javascript;

import javax.servlet.http.HttpServletRequest;

import junit.framework.Assert;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.transport.HttpTransport;
import org.junit.Test;

public class CometDURLPathTest extends AbstractCometDTest
{
    @Override
    protected void customizeBayeux(BayeuxServerImpl bayeux)
    {
        bayeux.addExtension(new BayeuxURLExtension(bayeux));
    }

    @Test
    public void testURLPath() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("var handshake = undefined;");
        evaluateScript("var connect = undefined;");
        evaluateScript("cometd.addListener('/meta/handshake', function(message) { handshake = message; });");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { connect = message; connectLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "/', logLevel: 'debug'})");
        Assert.assertTrue(connectLatch.await(1000));

        evaluateScript("window.assert(handshake !== undefined, 'handshake is undefined');");
        evaluateScript("window.assert(handshake.ext !== undefined, 'handshake without ext');");
        String handshakeURI = evaluateScript("handshake.ext.uri");
        Assert.assertTrue(handshakeURI.endsWith("/handshake"));

        evaluateScript("window.assert(connect !== undefined, 'connect is undefined');");
        evaluateScript("window.assert(connect.ext !== undefined, 'connect without ext');");
        String connectURI = evaluateScript("connect.ext.uri");
        Assert.assertTrue(connectURI.endsWith("/connect"));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("var disconnect = undefined;");
        evaluateScript("cometd.addListener('/meta/disconnect', function(message) { disconnect = message; disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(1000));

        evaluateScript("window.assert(disconnect !== undefined, 'disconnect is undefined');");
        evaluateScript("window.assert(disconnect.ext !== undefined, 'disconnect without ext');");
        String disconnectURI = evaluateScript("disconnect.ext.uri");
        Assert.assertTrue(disconnectURI.endsWith("/disconnect"));
    }

    @Test
    public void testURLPathWithFile() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("var handshake = undefined;");
        evaluateScript("var connect = undefined;");
        evaluateScript("cometd.addListener('/meta/handshake', function(message) { handshake = message; });");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { connect = message; connectLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "/target.cometd', logLevel: 'debug'})");
        Assert.assertTrue(connectLatch.await(1000));

        evaluateScript("window.assert(handshake !== undefined, 'handshake is undefined');");
        evaluateScript("window.assert(handshake.ext !== undefined, 'handshake without ext');");
        String handshakeURI = evaluateScript("handshake.ext.uri");
        Assert.assertFalse(handshakeURI.endsWith("/handshake"));

        evaluateScript("window.assert(connect !== undefined, 'connect is undefined');");
        evaluateScript("window.assert(connect.ext !== undefined, 'connect without ext');");
        String connectURI = evaluateScript("connect.ext.uri");
        Assert.assertFalse(connectURI.endsWith("/connect"));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("var disconnect = undefined;");
        evaluateScript("cometd.addListener('/meta/disconnect', function(message) { disconnect = message; disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(1000));

        evaluateScript("window.assert(disconnect !== undefined, 'disconnect is undefined');");
        evaluateScript("window.assert(disconnect.ext !== undefined, 'disconnect without ext');");
        String disconnectURI = evaluateScript("disconnect.ext.uri");
        Assert.assertFalse(disconnectURI.endsWith("/disconnect"));
    }

    @Test
    public void testURLPathWithParameters() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("var handshake = undefined;");
        evaluateScript("var connect = undefined;");
        evaluateScript("cometd.addListener('/meta/handshake', function(message) { handshake = message; });");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { connect = message; connectLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "/?param=1', logLevel: 'debug'})");
        Assert.assertTrue(connectLatch.await(1000));

        evaluateScript("window.assert(handshake !== undefined, 'handshake is undefined');");
        evaluateScript("window.assert(handshake.ext !== undefined, 'handshake without ext');");
        String handshakeURI = evaluateScript("handshake.ext.uri");
        Assert.assertFalse(handshakeURI.endsWith("/handshake"));

        evaluateScript("window.assert(connect !== undefined, 'connect is undefined');");
        evaluateScript("window.assert(connect.ext !== undefined, 'connect without ext');");
        String connectURI = evaluateScript("connect.ext.uri");
        Assert.assertFalse(connectURI.endsWith("/connect"));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("var disconnect = undefined;");
        evaluateScript("cometd.addListener('/meta/disconnect', function(message) { disconnect = message; disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(1000));

        evaluateScript("window.assert(disconnect !== undefined, 'disconnect is undefined');");
        evaluateScript("window.assert(disconnect.ext !== undefined, 'disconnect without ext');");
        String disconnectURI = evaluateScript("disconnect.ext.uri");
        Assert.assertFalse(disconnectURI.endsWith("/disconnect"));
    }

    @Test
    public void testURLPathDisabled() throws Exception
    {
        defineClass(Latch.class);
        evaluateScript("var connectLatch = new Latch(1);");
        Latch connectLatch = get("connectLatch");
        evaluateScript("var handshake = undefined;");
        evaluateScript("var connect = undefined;");
        evaluateScript("cometd.addListener('/meta/handshake', function(message) { handshake = message; });");
        evaluateScript("cometd.addListener('/meta/connect', function(message) { connect = message; connectLatch.countDown(); });");
        evaluateScript("cometd.init({url: '" + cometdURL + "/', logLevel: 'debug', appendMessageTypeToURL: false})");
        Assert.assertTrue(connectLatch.await(1000));

        evaluateScript("window.assert(handshake !== undefined, 'handshake is undefined');");
        evaluateScript("window.assert(handshake.ext !== undefined, 'handshake without ext');");
        String handshakeURI = evaluateScript("handshake.ext.uri");
        Assert.assertFalse(handshakeURI.endsWith("/handshake"));

        evaluateScript("window.assert(connect !== undefined, 'connect is undefined');");
        evaluateScript("window.assert(connect.ext !== undefined, 'connect without ext');");
        String connectURI = evaluateScript("connect.ext.uri");
        Assert.assertFalse(connectURI.endsWith("/connect"));

        evaluateScript("var disconnectLatch = new Latch(1);");
        Latch disconnectLatch = get("disconnectLatch");
        evaluateScript("var disconnect = undefined;");
        evaluateScript("cometd.addListener('/meta/disconnect', function(message) { disconnect = message; disconnectLatch.countDown(); });");
        evaluateScript("cometd.disconnect();");
        Assert.assertTrue(disconnectLatch.await(1000));

        evaluateScript("window.assert(disconnect !== undefined, 'disconnect is undefined');");
        evaluateScript("window.assert(disconnect.ext !== undefined, 'disconnect without ext');");
        String disconnectURI = evaluateScript("disconnect.ext.uri");
        Assert.assertFalse(disconnectURI.endsWith("/disconnect"));
    }

    public static class BayeuxURLExtension implements Extension
    {
        private final BayeuxServerImpl bayeux;

        public BayeuxURLExtension(BayeuxServerImpl bayeux)
        {
            this.bayeux = bayeux;
        }

        public boolean rcv(ServerSession from, Mutable message)
        {
            return true;
        }

        public boolean rcvMeta(ServerSession from, Mutable message)
        {
            return true;
        }

        public boolean send(ServerSession from, ServerSession to, Mutable message)
        {
            return true;
        }

        public boolean sendMeta(ServerSession to, Mutable message)
        {
            if (Channel.META_HANDSHAKE.equals(message.getChannel()) ||
                Channel.META_CONNECT.equals(message.getChannel()) ||
                Channel.META_DISCONNECT.equals(message.getChannel()))
            {
                HttpTransport transport = (HttpTransport)bayeux.getCurrentTransport();
                HttpServletRequest request = transport.getCurrentRequest();
                String uri = request.getRequestURI();
                message.getExt(true).put("uri", uri);
            }
            return true;
        }
    }
}
