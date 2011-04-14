package org.cometd.oort;

import java.net.URI;

import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.server.Server;
import org.junit.Assert;
import org.junit.Test;

public class OortObserveCometTest extends OortTest
{
    @Test
    public void testObserveStartedOortAndExpectToBeObserved() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertEquals(1, oort2.getKnownComets().size());

        OortComet oortComet21 = oort2.getComet(oort2.getKnownComets().iterator().next());
        Assert.assertNotNull(oortComet21);
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));
    }

    @Test
    public void testCometACometBConnectedWhenCometAConnectsToCometCThenAlsoCometBConnectsToCometC() throws Exception
    {
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(0);
        Oort oortB = startOort(serverB);

        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));

        Server serverC = startServer(0);
        Oort oortC = startOort(serverC);
        OortComet oortCometAC = oortA.observeComet(oortC.getURL());
        Assert.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait a while for the link B-C to establish
        Thread.sleep(1000);

        Assert.assertEquals(2, oortA.getKnownComets().size());
        Assert.assertEquals(2, oortB.getKnownComets().size());
        Assert.assertEquals(2, oortC.getKnownComets().size());
    }

    @Test
    public void testConnectTwoCloudsAndDisconnectOneComet() throws Exception
    {
        // Cloud #1, A and B
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(0);
        Oort oortB = startOort(serverB);
        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Cloud #2, C and D
        Server serverC = startServer(0);
        Oort oortC = startOort(serverC);
        Server serverD = startServer(0);
        Oort oortD = startOort(serverD);
        OortComet oortCometCD = oortC.observeComet(oortD.getURL());
        Assert.assertTrue(oortCometCD.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Now connect A and C, then we must see that A connects to D and B connects to C and D
        OortComet oortCometAC = oortA.observeComet(oortC.getURL());
        Assert.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait a while for the links to establish
        Thread.sleep(1000);

        Assert.assertEquals(3, oortA.getKnownComets().size());
        Assert.assertEquals(3, oortB.getKnownComets().size());
        Assert.assertEquals(3, oortC.getKnownComets().size());
        Assert.assertEquals(3, oortD.getKnownComets().size());

        // Remove comet C, then A and B must still be connected to D
        stopOort(oortC);
        stopServer(serverC);

        // Wait a while for the notifications to happen
        Thread.sleep(1000);

        Assert.assertEquals(2, oortA.getKnownComets().size());
        Assert.assertEquals(2, oortB.getKnownComets().size());
        Assert.assertEquals(2, oortD.getKnownComets().size());
    }

    @Test
    public void testObserveStartedOortAndDetectStop() throws Exception
    {
        Server server1 = startServer(0);
        Server server2 = startServer(0);

        Oort oort1 = startOort(server1);
        Assert.assertNotNull(oort1.getOortSession());
        Assert.assertTrue(oort1.getOortSession().isConnected());

        Oort oort2 = startOort(server2);

        Assert.assertNull(oort1.observeComet(oort1.getURL()));

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertNotNull(oortComet12);
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertEquals(1, oort1.getKnownComets().size());

        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assert.assertNotNull(oortComet21);
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        stopOort(oort2);
        Assert.assertFalse(oort2.getOortSession().isConnected());
        Assert.assertTrue(oort2.getHttpClient().isStopped());
        Assert.assertEquals(0, oort2.getKnownComets().size());

        // Stopping oort2 implies oort1 must see it disappearing
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assert.assertEquals(0, oort1.getKnownComets().size());
    }

    @Test
    public void testObserveNonStartedOortAndDetectStart() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(0);
        String url = (String)server2.getAttribute(OortServlet.OORT_URL_PARAM);
        int port = new URI(url).getPort();
        stopServer(server2);

        OortComet oortComet12 = oort1.observeComet(url);
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.REHANDSHAKING));

        server2 = startServer(port);
        Oort oort2 = startOort(server2);
        Assert.assertEquals(oort2.getURL(), url);

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
    }

    @Test
    public void testObserveStartedOortAndDetectRestart() throws Exception
    {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        int port2 = new URI(oort2.getURL()).getPort();
        stopServer(server2);

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        server2.getConnectors()[0].setPort(port2);
        server2.start();

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
    }

    @Test
    public void testDeobserve() throws Exception
    {
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(0);
        Oort oortB = startOort(serverB);

        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));

        Server serverC = startServer(0);
        Oort oortC = startOort(serverC);
        OortComet oortCometAC = oortA.observeComet(oortC.getURL());
        Assert.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait a while for the link B-C to establish
        Thread.sleep(1000);

        Assert.assertEquals(2, oortA.getKnownComets().size());
        Assert.assertTrue(oortA.getKnownComets().contains(oortB.getURL()));
        Assert.assertTrue(oortA.getKnownComets().contains(oortC.getURL()));
        Assert.assertEquals(2, oortB.getKnownComets().size());
        Assert.assertTrue(oortB.getKnownComets().contains(oortA.getURL()));
        Assert.assertTrue(oortB.getKnownComets().contains(oortC.getURL()));
        Assert.assertEquals(2, oortC.getKnownComets().size());
        Assert.assertTrue(oortC.getKnownComets().contains(oortA.getURL()));
        Assert.assertTrue(oortC.getKnownComets().contains(oortB.getURL()));

        BayeuxClient clientA = startClient(oortA);
        // Be sure that disconnecting clientA we do not mess with the known comets
        stopClient(clientA);

        Assert.assertEquals(2, oortA.getKnownComets().size());
        Assert.assertEquals(2, oortB.getKnownComets().size());
        Assert.assertEquals(2, oortC.getKnownComets().size());

        // Deobserve A-B
        OortComet cometAB = oortA.deobserveComet(oortB.getURL());
        Assert.assertSame(oortCometAB, cometAB);
        Assert.assertTrue(cometAB.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // A is now only connected to C
        Assert.assertEquals(1, oortA.getKnownComets().size());
        Assert.assertTrue(oortA.getKnownComets().contains(oortC.getURL()));
        // B is now only connected to C
        Assert.assertEquals(1, oortB.getKnownComets().size());
        Assert.assertTrue(oortB.getKnownComets().contains(oortC.getURL()));
        // C is still connected to A and B
        Assert.assertEquals(2, oortC.getKnownComets().size());
        Assert.assertTrue(oortC.getKnownComets().contains(oortA.getURL()));
        Assert.assertTrue(oortC.getKnownComets().contains(oortB.getURL()));
    }
}
