/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.Assert;
import org.junit.Test;

public class OortObserveCometTest extends OortTest {
    public OortObserveCometTest(String serverTransport) {
        super(serverTransport);
    }

    @Test
    public void testObserveStartedOortAndExpectToBeObserved() throws Exception {
        Server server1 = startServer(0);
        final Oort oort1 = startOort(server1);
        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assert.assertNotNull(oortComet21);
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertEquals(1, oort1.getKnownComets().size());
        Assert.assertEquals(1, oort2.getKnownComets().size());
    }

    @Test
    public void testCometACometBConnectedWhenCometAConnectsToCometCThenAlsoCometBConnectsToCometC() throws Exception {
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(0);
        Oort oortB = startOort(serverB);

        CountDownLatch latch = new CountDownLatch(6);
        Oort.CometListener listener = new CometJoinedListener(latch);
        oortA.addCometListener(listener);
        oortB.addCometListener(listener);

        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));

        Server serverC = startServer(0);
        Oort oortC = startOort(serverC);

        oortC.addCometListener(listener);

        OortComet oortCometAC = oortA.observeComet(oortC.getURL());
        Assert.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Be sure all the links have established
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assert.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCA = oortC.findComet(oortA.getURL());
        Assert.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCB = oortC.findComet(oortB.getURL());
        Assert.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometBC = oortB.findComet(oortC.getURL());
        Assert.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertEquals(2, oortA.getKnownComets().size());
        Assert.assertEquals(2, oortB.getKnownComets().size());
        Assert.assertEquals(2, oortC.getKnownComets().size());
    }

    @Test
    public void testConnectTwoCloudsAndDisconnectOneComet() throws Exception {
        // Cloud #1, A and B
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(0);
        Oort oortB = startOort(serverB);
        CountDownLatch latch1 = new CountDownLatch(2);
        CometJoinedListener listener1 = new CometJoinedListener(latch1);
        oortA.addCometListener(listener1);
        oortB.addCometListener(listener1);
        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assert.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Cloud #2, C and D
        Server serverC = startServer(0);
        Oort oortC = startOort(serverC);
        Server serverD = startServer(0);
        Oort oortD = startOort(serverD);
        CountDownLatch latch2 = new CountDownLatch(2);
        CometJoinedListener listener2 = new CometJoinedListener(latch2);
        oortC.addCometListener(listener2);
        oortD.addCometListener(listener2);
        OortComet oortCometCD = oortC.observeComet(oortD.getURL());
        Assert.assertTrue(oortCometCD.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        OortComet oortCometDC = oortD.findComet(oortC.getURL());
        Assert.assertTrue(oortCometDC.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Now connect A and C, then we must see that A connects to D and B connects to C and D
        CountDownLatch latch3 = new CountDownLatch(8);
        CometJoinedListener listener3 = new CometJoinedListener(latch3);
        oortA.addCometListener(listener3);
        oortB.addCometListener(listener3);
        oortC.addCometListener(listener3);
        oortD.addCometListener(listener3);
        OortComet oortCometAC = oortA.observeComet(oortC.getURL());
        Assert.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch3.await(5, TimeUnit.SECONDS));
        OortComet oortCometAD = oortA.findComet(oortD.getURL());
        Assert.assertTrue(oortCometAD.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometBC = oortB.findComet(oortC.getURL());
        Assert.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometBD = oortB.findComet(oortD.getURL());
        Assert.assertTrue(oortCometBD.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCA = oortC.findComet(oortA.getURL());
        Assert.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCB = oortC.findComet(oortB.getURL());
        Assert.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometDA = oortD.findComet(oortA.getURL());
        Assert.assertTrue(oortCometDA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometDB = oortD.findComet(oortB.getURL());
        Assert.assertTrue(oortCometDB.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertEquals(3, oortA.getKnownComets().size());
        Assert.assertEquals(3, oortB.getKnownComets().size());
        Assert.assertEquals(3, oortC.getKnownComets().size());
        Assert.assertEquals(3, oortD.getKnownComets().size());

        // Remove comet C, then A and B must still be connected to D
        CountDownLatch latch4 = new CountDownLatch(3);
        CometLeftListener listener4 = new CometLeftListener(latch4);
        oortA.addCometListener(listener4);
        oortB.addCometListener(listener4);
        oortD.addCometListener(listener4);
        stopOort(oortC);
        stopServer(serverC);
        Assert.assertTrue(latch4.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assert.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assert.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assert.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assert.assertTrue(oortCometDC.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assert.assertTrue(oortCometCD.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        Assert.assertEquals(2, oortA.getKnownComets().size());
        Assert.assertEquals(2, oortB.getKnownComets().size());
        Assert.assertEquals(2, oortD.getKnownComets().size());
    }

    @Test
    public void testObserveStartedOortAndDetectStop() throws Exception {
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
        Assert.assertEquals(0, oort2.getKnownComets().size());

        // Stopping oort2 implies oort1 must see it disappearing
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assert.assertEquals(0, oort1.getKnownComets().size());
    }

    @Test
    public void testObserveNonStartedOortAndDetectStart() throws Exception {
        Server server1 = startServer(0);
        String url = (String)server1.getAttribute(OortConfigServlet.OORT_URL_PARAM);
        final BayeuxServer bayeuxServer = (BayeuxServer)server1.getAttribute(BayeuxServer.ATTRIBUTE);
        Oort oort1 = new Oort(bayeuxServer, url);
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
    public void testObserveStartedOortAndDetectRestart() throws Exception {
        Server server1 = startServer(0);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(0);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        ServerConnector connector2 = (ServerConnector)server2.getConnectors()[0];
        int port2 = connector2.getLocalPort();
        connector2.stop();

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        connector2.setPort(port2);
        connector2.start();

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
    }

    @Test
    public void testNetworkBrokenShorterThanMaxInterval() throws Exception {
        long maxInterval = 4000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));

        Server server1 = startServer(0, options);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(0, options);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        ServerConnector connector1 = (ServerConnector)server1.getConnectors()[0];
        int port1 = connector1.getLocalPort();
        connector1.stop();

        ServerConnector connector2 = (ServerConnector)server2.getConnectors()[0];
        int port2 = connector2.getLocalPort();
        connector2.stop();

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.UNCONNECTED));
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Wait to let the comets handle the disconnection.
        Thread.sleep(maxInterval / 4);

        // Make sure that on reconnect the system does not emit a comet left event.
        CountDownLatch leftLatch = new CountDownLatch(1);
        oort1.addCometListener(new CometLeftListener(leftLatch));

        connector1.setPort(port1);
        connector1.start();

        connector2.setPort(port2);
        connector2.start();

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait until the maxInterval expires.
        Thread.sleep(maxInterval);

        // Verify that no comet left event has been emitted.
        Assert.assertFalse(leftLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testNetworkBrokenLongerThanMaxInterval() throws Exception {
        long maxInterval = 2000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));

        Server server1 = startServer(0, options);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(0, options);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        ServerConnector connector1 = (ServerConnector)server1.getConnectors()[0];
        int port1 = connector1.getLocalPort();
        connector1.stop();

        ServerConnector connector2 = (ServerConnector)server2.getConnectors()[0];
        int port2 = connector2.getLocalPort();
        connector2.stop();

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.UNCONNECTED));
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Wait until the servers sweep the sessions.
        Thread.sleep(2 * maxInterval);

        connector1.setPort(port1);
        connector1.start();

        connector2.setPort(port2);
        connector2.start();

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));
    }

    @Test
    public void testCometDownLongerThanMaxInterval() throws Exception {
        long maxInterval = 2000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));

        Server server1 = startServer(0, options);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(0, options);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Kill one comet.
        ServerConnector connector1 = (ServerConnector)server1.getConnectors()[0];
        int port1 = connector1.getLocalPort();
        connector1.stop();
        // Break connectivity to avoid graceful shutdown when stopping the comet.
        ServerConnector connector2 = (ServerConnector)server2.getConnectors()[0];
        int port2 = connector2.getLocalPort();
        connector2.stop();
        stopOort(oort1);
        stopServer(server1);

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Make sure that on reconnect the system emits
        // comet events for the right comet at the right time.
        final String oortId1 = oort1.getId();
        final String oortURL1 = oort1.getURL();
        final CountDownLatch joinedLatch = new CountDownLatch(1);
        final CountDownLatch leftLatch = new CountDownLatch(1);
        Oort.CometListener cometListener = new Oort.CometListener() {
            @Override
            public void cometLeft(Event event) {
                Assert.assertEquals(oortId1, event.getCometId());
                Assert.assertEquals(oortURL1, event.getCometURL());
                // Left event must happen before joined event.
                Assert.assertTrue(joinedLatch.getCount() > 0);
                leftLatch.countDown();
            }

            @Override
            public void cometJoined(Event event) {
                Assert.assertNotEquals(oortId1, event.getCometId());
                Assert.assertEquals(oortURL1, event.getCometURL());
                // Left event must happen before joined event.
                Assert.assertEquals(0, leftLatch.getCount());
                joinedLatch.countDown();
            }
        };
        oort2.addCometListener(cometListener);

        // Wait until the maxInterval expires.
        Thread.sleep(2 * maxInterval);

        // Restart the comet.
        server1 = startServer(port1, options);
        oort1 = startOort(server1);

        // Restore the connectivity.
        connector2.setPort(port2);
        connector2.start();

        // Poll until the connection is restored.
        int polls = 0;
        do {
            oortComet12 = oort1.getComet(oort2.getURL());
            Thread.sleep(100);
            ++polls;
        }
        while (oortComet12 == null && polls < 100);
        Assert.assertNotNull(oortComet12);

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Verify that comet events have been emitted.
        Assert.assertTrue(leftLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(joinedLatch.await(5, TimeUnit.SECONDS));

        // Avoid assertions while stopping the test.
        oort2.removeCometListener(cometListener);
    }

    @Test
    public void testCometDownShorterThanMaxInterval() throws Exception {
        long maxInterval = 4000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));

        Server server1 = startServer(0, options);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(0, options);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Kill one comet.
        ServerConnector connector1 = (ServerConnector)server1.getConnectors()[0];
        int port1 = connector1.getLocalPort();
        connector1.stop();
        // Break connectivity to avoid graceful shutdown when stopping the comet.
        ServerConnector connector2 = (ServerConnector)server2.getConnectors()[0];
        int port2 = connector2.getLocalPort();
        connector2.stop();
        stopOort(oort1);
        stopServer(server1);

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Wait to let the comets disconnect.
        Thread.sleep(maxInterval / 4);

        final String oortId1 = oort1.getId();

        // Restart the comet.
        server1 = startServer(port1, options);
        oort1 = startOort(server1);

        // Make sure that on reconnect the system emits a comet
        // left event for the right comet at the right time.

        CountDownLatch joinedLatch1 = new CountDownLatch(1);
        oort1.addCometListener(new CometJoinedListener(joinedLatch1));

        final String newOortId1 = oort1.getId();
        final String oortURL1 = oort1.getURL();
        final CountDownLatch joinedLatch2 = new CountDownLatch(1);
        final CountDownLatch leftLatch2 = new CountDownLatch(1);
        Oort.CometListener cometListener = new Oort.CometListener() {
            @Override
            public void cometLeft(Event event) {
                Assert.assertEquals(oortId1, event.getCometId());
                Assert.assertEquals(oortURL1, event.getCometURL());
                // Left event must happen before joined event.
                Assert.assertTrue(joinedLatch2.getCount() > 0);
                leftLatch2.countDown();
            }

            @Override
            public void cometJoined(Event event) {
                Assert.assertEquals(newOortId1, event.getCometId());
                Assert.assertEquals(oortURL1, event.getCometURL());
                // Left event must happen before joined event.
                Assert.assertEquals(0, leftLatch2.getCount());
                joinedLatch2.countDown();
            }
        };
        oort2.addCometListener(cometListener);

        // Restore the connectivity.
        connector2.setPort(port2);
        connector2.start();

        // Wait until the maxInterval expires, meanwhile the comets reconnect.
        Thread.sleep(maxInterval);

        oortComet12 = oort1.getComet(oort2.getURL());
        Assert.assertNotNull(oortComet12);

        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Verify that comet events have been emitted.
        Assert.assertTrue(leftLatch2.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(joinedLatch2.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(joinedLatch1.await(5, TimeUnit.SECONDS));

        List<String> oortIds = oort2.knownOortIds();
        Assert.assertEquals(1, oortIds.size());
        Assert.assertEquals(newOortId1, oortIds.get(0));

        // Avoid assertions while stopping the test.
        oort2.removeCometListener(cometListener);
    }

    @Test
    public void testDeobserve() throws Exception {
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(0);
        Oort oortB = startOort(serverB);
        Server serverC = startServer(0);
        Oort oortC = startOort(serverC);

        CountDownLatch latch1 = new CountDownLatch(6);
        CometJoinedListener listener1 = new CometJoinedListener(latch1);
        oortA.addCometListener(listener1);
        oortB.addCometListener(listener1);
        oortC.addCometListener(listener1);

        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometAC = oortA.observeComet(oortC.getURL());
        Assert.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assert.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometBC = oortB.findComet(oortC.getURL());
        Assert.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCA = oortC.findComet(oortA.getURL());
        Assert.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCB = oortC.findComet(oortB.getURL());
        Assert.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.CONNECTED));

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
        Assert.assertTrue(clientA.waitFor(5000, BayeuxClient.State.CONNECTED));
        // Be sure that disconnecting clientA we do not mess with the known comets
        stopClient(clientA);

        Assert.assertEquals(2, oortA.getKnownComets().size());
        Assert.assertEquals(2, oortB.getKnownComets().size());
        Assert.assertEquals(2, oortC.getKnownComets().size());

        // Deobserve A-B
        CountDownLatch latch2 = new CountDownLatch(2);
        CometLeftListener listener2 = new CometLeftListener(latch2);
        oortA.addCometListener(listener2);
        oortB.addCometListener(listener2);
        OortComet cometAB = oortA.deobserveComet(oortB.getURL());
        Assert.assertSame(oortCometAB, cometAB);
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        latch2.await(5, TimeUnit.SECONDS);
        Assert.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.DISCONNECTED));

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
    public void testObserveSameCometWithDifferentURL() throws Exception {
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        String urlA = oortA.getURL();
        String urlAA = urlA.replace("localhost", "127.0.0.1");

        OortComet oortCometAB = oortA.observeComet(urlAA);

        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @Test
    public void testObserveDifferentCometWithDifferentURL() throws Exception {
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(0);
        Oort oortB = startOort(serverB);

        String urlB = oortB.getURL();
        String otherURLB = urlB.replace("localhost", "127.0.0.1");

        CountDownLatch latch = new CountDownLatch(2);
        CometJoinedListener listener = new CometJoinedListener(latch);
        oortA.addCometListener(listener);
        oortB.addCometListener(listener);

        OortComet oortCometAB = oortA.observeComet(otherURLB);
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assert.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertEquals(1, oortB.getKnownComets().size());

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

    @Test
    public void testSingleCometsJoinsTheCloud() throws Exception {
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(0);
        Oort oortB = startOort(serverB);
        Server serverC = startServer(0);
        Oort oortC = startOort(serverC);
        Server serverD = startServer(0);
        Oort oortD = startOort(serverD);

        CountDownLatch latch1 = new CountDownLatch(2);
        CometJoinedListener listener1 = new CometJoinedListener(latch1);
        oortA.addCometListener(listener1);
        oortB.addCometListener(listener1);

        // Make a "cloud" connecting A and B
        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assert.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Now C wants to join the cloud, and connects to B
        CountDownLatch latch2 = new CountDownLatch(4);
        CometJoinedListener listener2 = new CometJoinedListener(latch2);
        oortA.addCometListener(listener2);
        oortB.addCometListener(listener2);
        oortC.addCometListener(listener2);
        OortComet oortCometCB = oortC.observeComet(oortB.getURL());
        Assert.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        OortComet oortCometBC = oortB.findComet(oortC.getURL());
        Assert.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometAC = oortA.findComet(oortC.getURL());
        Assert.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCA = oortC.findComet(oortA.getURL());
        Assert.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Make sure C is connected to B also
        Assert.assertEquals(2, oortA.getKnownComets().size());
        Assert.assertEquals(2, oortB.getKnownComets().size());
        Assert.assertEquals(2, oortC.getKnownComets().size());

        // Now also D want to join the cloud, and connects to B
        CountDownLatch latch3 = new CountDownLatch(6);
        CometJoinedListener listener3 = new CometJoinedListener(latch3);
        oortA.addCometListener(listener3);
        oortB.addCometListener(listener3);
        oortC.addCometListener(listener3);
        oortD.addCometListener(listener3);
        OortComet oortCometDB = oortD.observeComet(oortB.getURL());
        Assert.assertTrue(oortCometDB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch3.await(5, TimeUnit.SECONDS));
        OortComet oortCometBD = oortB.findComet(oortD.getURL());
        Assert.assertTrue(oortCometBD.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometAD = oortA.findComet(oortD.getURL());
        Assert.assertTrue(oortCometAD.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometDA = oortD.findComet(oortA.getURL());
        Assert.assertTrue(oortCometDA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometDC = oortD.findComet(oortC.getURL());
        Assert.assertTrue(oortCometDC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCD = oortC.findComet(oortD.getURL());
        Assert.assertTrue(oortCometCD.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Make sure C is connected to B also
        Assert.assertEquals(3, oortA.getKnownComets().size());
        Assert.assertEquals(3, oortB.getKnownComets().size());
        Assert.assertEquals(3, oortC.getKnownComets().size());
        Assert.assertEquals(3, oortD.getKnownComets().size());
    }

    @Test
    public void testAckExtensionConfiguration() throws Exception {
        Server serverA = startServer(0);
        Oort oortA = startOort(serverA);
        stopOort(oortA);
        oortA.setAckExtensionEnabled(true);
        oortA.start();

        BayeuxServer bayeuxServerA = oortA.getBayeuxServer();
        int ackExtensions = 0;
        for (BayeuxServer.Extension extension : bayeuxServerA.getExtensions()) {
            if (extension instanceof AcknowledgedMessagesExtension) {
                ++ackExtensions;
            }
        }
        Assert.assertEquals(1, ackExtensions);

        Server serverB = startServer(0);
        BayeuxServer bayeuxServerB = (BayeuxServer)serverB.getAttribute(BayeuxServer.ATTRIBUTE);
        bayeuxServerB.addExtension(new AcknowledgedMessagesExtension());
        Oort oortB = startOort(serverB);
        stopOort(oortB);
        oortB.setAckExtensionEnabled(true);
        oortB.start();

        ackExtensions = 0;
        for (BayeuxServer.Extension extension : bayeuxServerB.getExtensions()) {
            if (extension instanceof AcknowledgedMessagesExtension) {
                ++ackExtensions;
            }
        }
        Assert.assertEquals(1, ackExtensions);

        CountDownLatch latch1 = new CountDownLatch(2);
        CometJoinedListener listener1 = new CometJoinedListener(latch1);
        oortA.addCometListener(listener1);
        oortB.addCometListener(listener1);

        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assert.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assert.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assert.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));

        ackExtensions = 0;
        for (ClientSession.Extension extension : oortCometAB.getExtensions()) {
            if (extension instanceof AckExtension) {
                ++ackExtensions;
            }
        }
        Assert.assertEquals(1, ackExtensions);

        ackExtensions = 0;
        for (ClientSession.Extension extension : oortCometBA.getExtensions()) {
            if (extension instanceof AckExtension) {
                ++ackExtensions;
            }
        }
        Assert.assertEquals(1, ackExtensions);
    }

    @Test
    public void testPublishDuringCometJoined() throws Exception {
        Server serverA = startServer(0);
        final Oort oortA = startOort(serverA);
        final BayeuxServer bayeuxServerA = oortA.getBayeuxServer();

        final LocalSession serviceA = bayeuxServerA.newLocalSession("test");
        serviceA.handshake();
        final String channelName = "/test";
        final String data = "data";
        final CountDownLatch joinedLatch = new CountDownLatch(1);
        oortA.addCometListener(new Oort.CometListener.Adapter() {
            @Override
            public void cometJoined(Event event) {
                bayeuxServerA.createChannelIfAbsent(channelName).getReference().publish(serviceA, data);
                joinedLatch.countDown();
            }
        });
        oortA.observeChannel(channelName);

        Server serverB = startServer(0);
        Oort oortB = startOort(serverB);
        oortB.observeChannel(channelName);

        BayeuxServer bayeuxServerB = oortB.getBayeuxServer();
        final CountDownLatch latch = new CountDownLatch(1);
        bayeuxServerB.createChannelIfAbsent(channelName).getReference().addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                if (data.equals(message.getData())) {
                    latch.countDown();
                }
                return true;
            }
        });

        // Link the nodes
        oortB.observeComet(oortA.getURL());

        Assert.assertTrue(joinedLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testConfigureMaxMessageSize() throws Exception {
        int maxMessageSize = 1024;
        Map<String, String> options = new HashMap<>();
        options.put("ws.maxMessageSize", String.valueOf(maxMessageSize));
        Server serverA = startServer(0, options);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(0, options);
        Oort oortB = startOort(serverB);

        CountDownLatch latch = new CountDownLatch(2);
        oortA.addCometListener(new CometJoinedListener(latch));
        oortB.addCometListener(new CometJoinedListener(latch));

        OortComet oortComet12 = oortA.observeComet(oortB.getURL());
        Assert.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        OortComet oortComet21 = oortB.findComet(oortA.getURL());
        Assert.assertNotNull(oortComet21);
        Assert.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        String channelName = "/foo";
        oortA.observeChannel(channelName);
        oortB.observeChannel(channelName);

        BayeuxClient clientA = startClient(oortA, null);
        Assert.assertTrue(clientA.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient clientB = startClient(oortB, null);
        Assert.assertTrue(clientB.waitFor(5000, BayeuxClient.State.CONNECTED));

        final AtomicReference<CountDownLatch> messageLatch = new AtomicReference<>(new CountDownLatch(1));
        clientB.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener() {
            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                messageLatch.get().countDown();
            }
        });

        // Wait a while to be sure to be subscribed
        Thread.sleep(1000);

        char[] clob = new char[maxMessageSize / 2];
        Arrays.fill(clob, 'w');
        clientA.getChannel(channelName).publish(new String(clob));
        Assert.assertTrue(messageLatch.get().await(5, TimeUnit.SECONDS));

        // Make the message larger than allowed
        messageLatch.set(new CountDownLatch(1));
        clob = new char[maxMessageSize * 2];
        Arrays.fill(clob, 'z');
        clientA.getChannel(channelName).publish(new String(clob));
        Assert.assertFalse(messageLatch.get().await(1, TimeUnit.SECONDS));
    }
}
