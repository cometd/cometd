/*
 * Copyright (c) 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.oort;

import java.net.ConnectException;
import java.net.URI;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
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
        String url = (String)server1.getAttribute(OortConfigServlet.OORT_URL_PARAM);
        final BayeuxServer bayeuxServer = (BayeuxServer)server1.getAttribute(BayeuxServer.ATTRIBUTE);
        Oort oort1 = new Oort(bayeuxServer, url)
        {
            @Override
            protected OortComet newOortComet(String cometURL)
            {
                return new OortComet(this, cometURL)
                {
                    @Override
                    public void onFailure(Throwable x, Message[] messages)
                    {
                        // Suppress expected exceptions
                        if (!(x instanceof ConnectException))
                            super.onFailure(x, messages);
                    }
                };
            }
        };
        oort1.setClientDebugEnabled(Boolean.getBoolean("debugTests"));
        oort1.start();

        Server server2 = startServer(0);
        String url2 = (String)server2.getAttribute(OortConfigServlet.OORT_URL_PARAM);
        int port = new URI(url2).getPort();
        stopServer(server2);

        OortComet oortComet12 = oort1.observeComet(url2);
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.REHANDSHAKING));

        server2 = startServer(port);
        Oort oort2 = startOort(server2);
        Assert.assertEquals(oort2.getURL(), url2);

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        stopOort(oort1);
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

        BayeuxClient clientA = startClient(oortA, null);
        // Be sure that disconnecting clientA we do not mess with the known comets
        stopClient(clientA);

        Assert.assertEquals(2, oortA.getKnownComets().size());
        Assert.assertEquals(2, oortB.getKnownComets().size());
        Assert.assertEquals(2, oortC.getKnownComets().size());

        // Deobserve A-B
        OortComet cometAB = oortA.deobserveComet(oortB.getURL());
        Assert.assertSame(oortCometAB, cometAB);
        Assert.assertTrue(cometAB.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // Allow disconnections to happen
        Thread.sleep(1000);

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

    @Test
    public void testObserveSameCometWithDifferentURL() throws Exception
    {
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        String urlA = oortA.getURL();
        String urlAA = urlA.replace("localhost", "127.0.0.1");

        OortComet oortCometAB = oortA.observeComet(urlAA);

        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testObserveDifferentCometWithDifferentURL() throws Exception
    {
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(0);
        Oort oortB = startOort(serverB);

        String urlB = oortB.getURL();
        String otherURLB = urlB.replace("localhost", "127.0.0.1");

        OortComet oortCometAB = oortA.observeComet(otherURLB);
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait a while for the links to establish
        Thread.sleep(1000);

        Assert.assertEquals(1, oortB.getKnownComets().size());

        OortComet oortCometBA = oortB.getComet(oortB.getKnownComets().iterator().next());
        Assert.assertNotNull(oortCometBA);
        Assert.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Only master URLs are known
        Assert.assertFalse(oortA.getKnownComets().contains(otherURLB));
        Assert.assertTrue(oortA.getKnownComets().contains(urlB));
        // But comets can be known with alias URLs
        OortComet oortCometAB1 = oortA.getComet(urlB);
        Assert.assertNotNull(oortCometAB1);
        OortComet oortCometAB2 = oortA.getComet(otherURLB);
        Assert.assertNotNull(oortCometAB2);
        Assert.assertSame(oortCometAB, oortCometAB1);
        Assert.assertSame(oortCometAB1, oortCometAB2);

        // Now try with the original URL
        OortComet oortCometAB3 = oortA.observeComet(urlB);
        Assert.assertNotNull(oortCometAB3);
        Assert.assertSame(oortCometAB, oortCometAB3);

        // Try with yet another URL
        String anotherURLB = urlB.replace("localhost", "[::1]");
        OortComet oortCometAB4 = oortA.observeComet(anotherURLB);
        Assert.assertTrue(oortCometAB4.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        OortComet oortCometAB5 = oortA.getComet(anotherURLB);
        Assert.assertSame(oortCometAB, oortCometAB5);

        // Now disconnect with the original URL
        OortComet oortCometAB6 = oortA.deobserveComet(urlB);
        Assert.assertNotNull(oortCometAB6);
        Assert.assertTrue(oortCometAB6.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }
}
