/*
 * Copyright (c) 2008-2022 the original author or authors.
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ext.AckExtension;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportListener;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class OortObserveCometTest extends AbstractOortTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testObserveStartedOortAndExpectToBeObserved(Transport transport) throws Exception {
        Server server1 = startServer(transport, 0);
        Oort oort1 = startOort(server1);
        Server server2 = startServer(transport, 0);
        Oort oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertNotNull(oortComet21);
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertEquals(1, oort1.getKnownComets().size());
        Assertions.assertEquals(1, oort2.getKnownComets().size());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCometACometBConnectedWhenCometAConnectsToCometCThenAlsoCometBConnectsToCometC(Transport transport) throws Exception {
        Server serverA = startServer(transport, 0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(transport, 0);
        Oort oortB = startOort(serverB);

        CountDownLatch latch = new CountDownLatch(6);
        Oort.CometListener listener = new CometJoinedListener(latch);
        oortA.addCometListener(listener);
        oortB.addCometListener(listener);

        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));

        Server serverC = startServer(transport, 0);
        Oort oortC = startOort(serverC);

        oortC.addCometListener(listener);

        OortComet oortCometAC = oortA.observeComet(oortC.getURL());
        Assertions.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Be sure all the links have established
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCA = oortC.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCB = oortC.findComet(oortB.getURL());
        Assertions.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometBC = oortB.findComet(oortC.getURL());
        Assertions.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertEquals(2, oortA.getKnownComets().size());
        Assertions.assertEquals(2, oortB.getKnownComets().size());
        Assertions.assertEquals(2, oortC.getKnownComets().size());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testConnectTwoCloudsAndDisconnectOneComet(Transport transport) throws Exception {
        // Cloud #1, A and B
        Server serverA = startServer(transport, 0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(transport, 0);
        Oort oortB = startOort(serverB);
        CountDownLatch latch1 = new CountDownLatch(2);
        CometJoinedListener listener1 = new CometJoinedListener(latch1);
        oortA.addCometListener(listener1);
        oortB.addCometListener(listener1);
        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Cloud #2, C and D
        Server serverC = startServer(transport, 0);
        Oort oortC = startOort(serverC);
        Server serverD = startServer(transport, 0);
        Oort oortD = startOort(serverD);
        CountDownLatch latch2 = new CountDownLatch(2);
        CometJoinedListener listener2 = new CometJoinedListener(latch2);
        oortC.addCometListener(listener2);
        oortD.addCometListener(listener2);
        OortComet oortCometCD = oortC.observeComet(oortD.getURL());
        Assertions.assertTrue(oortCometCD.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        OortComet oortCometDC = oortD.findComet(oortC.getURL());
        Assertions.assertTrue(oortCometDC.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Now connect A and C, then we must see that A connects to D and B connects to C and D
        CountDownLatch latch3 = new CountDownLatch(8);
        CometJoinedListener listener3 = new CometJoinedListener(latch3);
        oortA.addCometListener(listener3);
        oortB.addCometListener(listener3);
        oortC.addCometListener(listener3);
        oortD.addCometListener(listener3);
        OortComet oortCometAC = oortA.observeComet(oortC.getURL());
        Assertions.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch3.await(5, TimeUnit.SECONDS));
        OortComet oortCometAD = oortA.findComet(oortD.getURL());
        Assertions.assertTrue(oortCometAD.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometBC = oortB.findComet(oortC.getURL());
        Assertions.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometBD = oortB.findComet(oortD.getURL());
        Assertions.assertTrue(oortCometBD.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCA = oortC.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCB = oortC.findComet(oortB.getURL());
        Assertions.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometDA = oortD.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometDA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometDB = oortD.findComet(oortB.getURL());
        Assertions.assertTrue(oortCometDB.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertEquals(3, oortA.getKnownComets().size());
        Assertions.assertEquals(3, oortB.getKnownComets().size());
        Assertions.assertEquals(3, oortC.getKnownComets().size());
        Assertions.assertEquals(3, oortD.getKnownComets().size());

        // Remove comet C, then A and B must still be connected to D
        CountDownLatch latch4 = new CountDownLatch(3);
        CometLeftListener listener4 = new CometLeftListener(latch4);
        oortA.addCometListener(listener4);
        oortB.addCometListener(listener4);
        oortD.addCometListener(listener4);
        stopOort(oortC);
        stopServer(serverC);
        Assertions.assertTrue(latch4.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assertions.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assertions.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assertions.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assertions.assertTrue(oortCometDC.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assertions.assertTrue(oortCometCD.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        Assertions.assertEquals(2, oortA.getKnownComets().size());
        Assertions.assertEquals(2, oortB.getKnownComets().size());
        Assertions.assertEquals(2, oortD.getKnownComets().size());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testObserveStartedOortAndDetectStop(Transport transport) throws Exception {
        Server server1 = startServer(transport, 0);
        Server server2 = startServer(transport, 0);

        Oort oort1 = startOort(server1);
        Assertions.assertNotNull(oort1.getOortSession());
        Assertions.assertTrue(oort1.getOortSession().isConnected());

        Oort oort2 = startOort(server2);

        Assertions.assertNull(oort1.observeComet(oort1.getURL()));

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertNotNull(oortComet12);
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertEquals(1, oort1.getKnownComets().size());

        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assertions.assertNotNull(oortComet21);
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        stopOort(oort2);
        Assertions.assertFalse(oort2.getOortSession().isConnected());
        Assertions.assertEquals(0, oort2.getKnownComets().size());

        // Stopping oort2 implies oort1 must see it disappearing
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assertions.assertEquals(0, oort1.getKnownComets().size());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testObserveNonStartedOortAndDetectStart(Transport transport) throws Exception {
        Server server1 = startServer(transport, 0);
        String url = (String)server1.getAttribute(OortConfigServlet.OORT_URL_PARAM);
        BayeuxServer bayeuxServer = (BayeuxServer)server1.getAttribute(BayeuxServer.ATTRIBUTE);
        Oort oort1 = new Oort(bayeuxServer, url);
        oort1.start();

        Server server2 = startServer(transport, 0);
        String url2 = (String)server2.getAttribute(OortConfigServlet.OORT_URL_PARAM);
        int port = new URI(url2).getPort();
        stopServer(server2);

        OortComet oortComet12 = oort1.observeComet(url2);
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.REHANDSHAKING));

        server2 = startServer(transport, port);
        Oort oort2 = startOort(server2);
        Assertions.assertEquals(oort2.getURL(), url2);

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        stopOort(oort1);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testObserveStartedOortAndDetectConnectorRestart(Transport transport) throws Exception {
        Server server1 = startServer(transport, 0);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(transport, 0);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for for comet events and for /meta/connect to be held.
        sleep(1000);

        CountDownLatch joinedLatch = new CountDownLatch(1);
        CometJoinedListener joinedListener = new CometJoinedListener(joinedLatch);
        oort1.addCometListener(joinedListener);
        oort2.addCometListener(joinedListener);
        CountDownLatch leftLatch = new CountDownLatch(1);
        CometLeftListener leftListener = new CometLeftListener(leftLatch);
        oort1.addCometListener(leftListener);
        oort2.addCometListener(leftListener);

        ServerConnector connector2 = (ServerConnector)server2.getConnectors()[0];
        int port2 = connector2.getLocalPort();
        connector2.stop();

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        connector2.setPort(port2);
        connector2.start();

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertFalse(joinedLatch.await(1, TimeUnit.SECONDS));
        Assertions.assertFalse(leftLatch.await(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testObserveStartedOortAndDetectServerRestart(Transport transport) throws Exception {
        Server serverA = startServer(transport, 0);
        Oort oortA = startOort(serverA);

        Server serverB = startServer(transport, 0);
        Oort oortB = startOort(serverB);

        OortComet oortCometAB1 = oortA.observeComet(oortB.getURL());
        Assertions.assertTrue(oortCometAB1.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oortB.observeComet(oortA.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for for comet events and for /meta/connect to be held.
        sleep(1000);

        AtomicInteger leftCountA = new AtomicInteger();
        CountDownLatch leftLatchA = new CountDownLatch(1);
        oortA.addCometListener(new Oort.CometListener() {
            @Override
            public void cometLeft(Event event) {
                leftCountA.incrementAndGet();
                leftLatchA.countDown();
            }
        });

        ServerConnector connector2 = (ServerConnector)serverB.getConnectors()[0];
        int port2 = connector2.getLocalPort();
        stopOort(oortB);
        oorts.remove(oortB);
        stopServer(serverB);

        Assertions.assertTrue(oortCometAB1.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assertions.assertTrue(leftLatchA.await(5, TimeUnit.SECONDS));
        // Wait for possibly more (wrong) comet events.
        sleep(1000);
        Assertions.assertEquals(1, leftCountA.get());

        serverB = startServer(transport, port2);
        String url2 = (String)serverB.getAttribute(OortConfigServlet.OORT_URL_PARAM);
        BayeuxServer bayeuxServer2 = (BayeuxServer)serverB.getAttribute(BayeuxServer.ATTRIBUTE);
        bayeuxServer2.setOption(Server.class.getName(), serverB);
        oortB = new Oort(bayeuxServer2, url2);
        oorts.add(oortB);
        AtomicInteger joinedCountA = new AtomicInteger();
        CountDownLatch joinedLatchA = new CountDownLatch(1);
        oortA.addCometListener(new Oort.CometListener() {
            @Override
            public void cometJoined(Event event) {
                joinedCountA.incrementAndGet();
                joinedLatchA.countDown();
            }
        });
        AtomicInteger joinedCountB = new AtomicInteger();
        CountDownLatch joinedLatchB = new CountDownLatch(1);
        oortB.addCometListener(new Oort.CometListener() {
            @Override
            public void cometJoined(Event event) {
                joinedCountB.incrementAndGet();
                joinedLatchB.countDown();
            }
        });
        oortB.start();
        OortComet oortCometAB2 = oortA.observeComet(oortB.getURL());
        Assertions.assertNotSame(oortCometAB1, oortCometAB2);

        Assertions.assertTrue(oortCometAB2.waitFor(5000, BayeuxClient.State.CONNECTED));
        // Wait for for comet events and for /meta/connect to be held.
        sleep(1000);

        Assertions.assertTrue(joinedLatchA.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(1, joinedCountA.get());
        Assertions.assertTrue(joinedLatchB.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(1, joinedCountB.get());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testNetworkBrokenShorterThanMaxInterval(Transport transport) throws Exception {
        long sweepPeriod = 500;
        long maxInterval = 4000;
        Map<String, String> options = new HashMap<>();
        options.put(BayeuxServerImpl.SWEEP_PERIOD_OPTION, String.valueOf(sweepPeriod));
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));

        Server server1 = startServer(transport, 0, options);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(transport, 0, options);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for for comet events and for /meta/connect to be held.
        sleep(1000);

        ServerConnector connector1 = (ServerConnector)server1.getConnectors()[0];
        int port1 = connector1.getLocalPort();
        connector1.stop();

        ServerConnector connector2 = (ServerConnector)server2.getConnectors()[0];
        int port2 = connector2.getLocalPort();
        connector2.stop();

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.UNCONNECTED));
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Wait to let the comets handle the disconnection.
        Thread.sleep(maxInterval / 4);

        // Make sure that on reconnect the system does not emit a comet left event.
        CountDownLatch leftLatch = new CountDownLatch(1);
        oort1.addCometListener(new CometLeftListener(leftLatch));

        connector1.setPort(port1);
        connector1.start();

        connector2.setPort(port2);
        connector2.start();

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait until the maxInterval expires.
        Thread.sleep(maxInterval + 2 * sweepPeriod);

        // Verify that no comet left event has been emitted.
        Assertions.assertFalse(leftLatch.await(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testNetworkBrokenLongerThanMaxInterval(Transport transport) throws Exception {
        long timeout = 2000;
        long maxInterval = 3000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));

        Server server1 = startServer(transport, 0, options);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(transport, 0, options);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the comet events and for /meta/connect to be held.
        sleep(1000);

        AtomicInteger joinCount = new AtomicInteger();
        CountDownLatch joinLatch = new CountDownLatch(2);
        AtomicInteger leftCount = new AtomicInteger();
        CountDownLatch leftLatch = new CountDownLatch(2);
        Oort.CometListener listener = new Oort.CometListener() {
            @Override
            public void cometJoined(Event event) {
                joinCount.incrementAndGet();
                joinLatch.countDown();
            }

            @Override
            public void cometLeft(Event event) {
                leftCount.incrementAndGet();
                leftLatch.countDown();
            }
        };
        oort1.addCometListener(listener);
        oort2.addCometListener(listener);

        ServerConnector connector1 = (ServerConnector)server1.getConnectors()[0];
        int port1 = connector1.getLocalPort();
        connector1.stop();

        ServerConnector connector2 = (ServerConnector)server2.getConnectors()[0];
        int port2 = connector2.getLocalPort();
        connector2.stop();

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.UNCONNECTED));
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Wait until the servers sweep the sessions.
        Thread.sleep(timeout + 2 * maxInterval);

        Assertions.assertTrue(leftLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(2, leftCount.get());

        connector1.setPort(port1);
        connector1.start();

        connector2.setPort(port2);
        connector2.start();

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for comet events.
        sleep(1000);

        Assertions.assertTrue(joinLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(2, joinCount.get());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCometDownLongerThanMaxInterval(Transport transport) throws Exception {
        long maxInterval = 2000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));

        Server server1 = startServer(transport, 0, options);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(transport, 0, options);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

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

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Make sure that on reconnect the system emits
        // comet events for the right comet at the right time.
        String oortId1 = oort1.getId();
        String oortURL1 = oort1.getURL();
        CountDownLatch joinedLatch = new CountDownLatch(1);
        CountDownLatch leftLatch = new CountDownLatch(1);
        Oort.CometListener cometListener = new Oort.CometListener() {
            @Override
            public void cometLeft(Event event) {
                Assertions.assertEquals(oortId1, event.getCometId());
                Assertions.assertEquals(oortURL1, event.getCometURL());
                // Left event must happen before joined event.
                Assertions.assertTrue(joinedLatch.getCount() > 0);
                leftLatch.countDown();
            }

            @Override
            public void cometJoined(Event event) {
                Assertions.assertNotEquals(oortId1, event.getCometId());
                Assertions.assertEquals(oortURL1, event.getCometURL());
                // Left event must happen before joined event.
                Assertions.assertEquals(0, leftLatch.getCount());
                joinedLatch.countDown();
            }
        };
        oort2.addCometListener(cometListener);

        // Wait until the maxInterval expires.
        Thread.sleep(2 * maxInterval);

        // Restart the comet.
        server1 = startServer(transport, port1, options);
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
        Assertions.assertNotNull(oortComet12);

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Verify that comet events have been emitted.
        Assertions.assertTrue(leftLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(joinedLatch.await(5, TimeUnit.SECONDS));

        // Avoid assertions while stopping the test.
        oort2.removeCometListener(cometListener);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testCometDownShorterThanMaxInterval(Transport transport) throws Exception {
        long maxInterval = 4000;
        Map<String, String> options = new HashMap<>();
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));

        Server server1 = startServer(transport, 0, options);
        Oort oort1 = startOort(server1);

        Server server2 = startServer(transport, 0, options);
        Oort oort2 = startOort(server2);

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortComet21 = oort2.observeComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

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

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.UNCONNECTED));

        // Wait to let the comets disconnect.
        Thread.sleep(maxInterval / 4);

        String oortId1 = oort1.getId();

        // Restart the comet.
        server1 = startServer(transport, port1, options);
        oort1 = startOort(server1);

        // Make sure that on reconnect the system emits a comet
        // left event for the right comet at the right time.

        CountDownLatch joinedLatch1 = new CountDownLatch(1);
        oort1.addCometListener(new CometJoinedListener(joinedLatch1));

        String newOortId1 = oort1.getId();
        String oortURL1 = oort1.getURL();
        CountDownLatch joinedLatch2 = new CountDownLatch(1);
        CountDownLatch leftLatch2 = new CountDownLatch(1);
        Oort.CometListener cometListener = new Oort.CometListener() {
            @Override
            public void cometLeft(Event event) {
                Assertions.assertEquals(oortId1, event.getCometId());
                Assertions.assertEquals(oortURL1, event.getCometURL());
                // Left event must happen before joined event.
                Assertions.assertTrue(joinedLatch2.getCount() > 0);
                leftLatch2.countDown();
            }

            @Override
            public void cometJoined(Event event) {
                Assertions.assertEquals(newOortId1, event.getCometId());
                Assertions.assertEquals(oortURL1, event.getCometURL());
                // Left event must happen before joined event.
                Assertions.assertEquals(0, leftLatch2.getCount());
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
        Assertions.assertNotNull(oortComet12);

        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Verify that comet events have been emitted.
        Assertions.assertTrue(leftLatch2.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(joinedLatch2.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(joinedLatch1.await(5, TimeUnit.SECONDS));

        List<String> oortIds = oort2.knownOortIds();
        Assertions.assertEquals(1, oortIds.size());
        Assertions.assertEquals(newOortId1, oortIds.get(0));

        // Avoid assertions while stopping the test.
        oort2.removeCometListener(cometListener);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDeobserve(Transport transport) throws Exception {
        Server serverA = startServer(transport, 0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(transport, 0);
        Oort oortB = startOort(serverB);
        Server serverC = startServer(transport, 0);
        Oort oortC = startOort(serverC);

        CountDownLatch latch1 = new CountDownLatch(6);
        CometJoinedListener listener1 = new CometJoinedListener(latch1);
        oortA.addCometListener(listener1);
        oortB.addCometListener(listener1);
        oortC.addCometListener(listener1);

        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometAC = oortA.observeComet(oortC.getURL());
        Assertions.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometBC = oortB.findComet(oortC.getURL());
        Assertions.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCA = oortC.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCB = oortC.findComet(oortB.getURL());
        Assertions.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertEquals(2, oortA.getKnownComets().size());
        Assertions.assertTrue(oortA.getKnownComets().contains(oortB.getURL()));
        Assertions.assertTrue(oortA.getKnownComets().contains(oortC.getURL()));
        Assertions.assertEquals(2, oortB.getKnownComets().size());
        Assertions.assertTrue(oortB.getKnownComets().contains(oortA.getURL()));
        Assertions.assertTrue(oortB.getKnownComets().contains(oortC.getURL()));
        Assertions.assertEquals(2, oortC.getKnownComets().size());
        Assertions.assertTrue(oortC.getKnownComets().contains(oortA.getURL()));
        Assertions.assertTrue(oortC.getKnownComets().contains(oortB.getURL()));

        BayeuxClient clientA = startClient(oortA, null);
        Assertions.assertTrue(clientA.waitFor(5000, BayeuxClient.State.CONNECTED));
        // Be sure that disconnecting clientA we do not mess with the known comets
        stopClient(clientA);

        Assertions.assertEquals(2, oortA.getKnownComets().size());
        Assertions.assertEquals(2, oortB.getKnownComets().size());
        Assertions.assertEquals(2, oortC.getKnownComets().size());

        // Deobserve A-B
        CountDownLatch latch2 = new CountDownLatch(2);
        CometLeftListener listener2 = new CometLeftListener(latch2);
        oortA.addCometListener(listener2);
        oortB.addCometListener(listener2);
        OortComet cometAB = oortA.deobserveComet(oortB.getURL());
        Assertions.assertSame(oortCometAB, cometAB);
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        Assertions.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // A is now only connected to C
        Assertions.assertEquals(1, oortA.getKnownComets().size());
        Assertions.assertTrue(oortA.getKnownComets().contains(oortC.getURL()));
        // B is now only connected to C
        Assertions.assertEquals(1, oortB.getKnownComets().size());
        Assertions.assertTrue(oortB.getKnownComets().contains(oortC.getURL()));
        // C is still connected to A and B
        Assertions.assertEquals(2, oortC.getKnownComets().size());
        Assertions.assertTrue(oortC.getKnownComets().contains(oortA.getURL()));
        Assertions.assertTrue(oortC.getKnownComets().contains(oortB.getURL()));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testObserveSameCometWithDifferentURL(Transport transport) throws Exception {
        Server serverA = startServer(transport, 0);
        Oort oortA = startOort(serverA);
        String urlA = oortA.getURL();
        String urlAA = urlA.replace("localhost", "127.0.0.1");

        OortComet oortCometAB = oortA.observeComet(urlAA);

        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testObserveDifferentCometWithDifferentURL(Transport transport) throws Exception {
        Assumptions.assumeTrue(ipv6Available());

        Server serverA = startServer(transport, 0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(transport, 0);
        Oort oortB = startOort(serverB);

        String urlB = oortB.getURL();
        String otherURLB = urlB.replace("localhost", "127.0.0.1");

        CountDownLatch latch = new CountDownLatch(2);
        CometJoinedListener listener = new CometJoinedListener(latch);
        oortA.addCometListener(listener);
        oortB.addCometListener(listener);

        OortComet oortCometAB = oortA.observeComet(otherURLB);
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertEquals(1, oortB.getKnownComets().size());

        // Only primary URLs are known.
        Assertions.assertFalse(oortA.getKnownComets().contains(otherURLB));
        Assertions.assertTrue(oortA.getKnownComets().contains(urlB));
        // But comets can be known with alias URLs
        OortComet oortCometAB1 = oortA.getComet(urlB);
        Assertions.assertNotNull(oortCometAB1);
        OortComet oortCometAB2 = oortA.getComet(otherURLB);
        Assertions.assertNotNull(oortCometAB2);
        Assertions.assertSame(oortCometAB, oortCometAB1);
        Assertions.assertSame(oortCometAB1, oortCometAB2);

        // Now try with the original URL.
        OortComet oortCometAB3 = oortA.observeComet(urlB);
        Assertions.assertNotNull(oortCometAB3);
        Assertions.assertSame(oortCometAB, oortCometAB3);

        // Try with yet another URL.
        // The previous OortComet will be disconnected, but since
        // it was already connected, no join/left events are emitted.
        BlockingQueue<Oort.CometListener.Event> cometEvents = new LinkedBlockingQueue<>();
        oortB.addCometListener(new Oort.CometListener() {
            @Override
            public void cometLeft(Event event) {
                cometEvents.offer(event);
            }

            @Override
            public void cometJoined(Event event) {
                cometEvents.offer(event);
            }
        });
        String anotherURLB = urlB.replace("localhost", "[::1]");
        OortComet oortCometAB4 = oortA.observeComet(anotherURLB);
        Assertions.assertTrue(oortCometAB4.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.DISCONNECTED));
        OortComet oortCometAB5 = oortA.getComet(anotherURLB);
        Assertions.assertSame(oortCometAB4, oortCometAB5);
        Assertions.assertNull(cometEvents.poll(1, TimeUnit.SECONDS));

        // Now disconnect with the original URL
        OortComet oortCometAB6 = oortA.deobserveComet(urlB);
        Assertions.assertNotNull(oortCometAB6);
        Assertions.assertSame(oortCometAB4, oortCometAB6);
        Assertions.assertTrue(oortCometAB6.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSingleCometsJoinsTheCloud(Transport transport) throws Exception {
        Server serverA = startServer(transport, 0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(transport, 0);
        Oort oortB = startOort(serverB);
        Server serverC = startServer(transport, 0);
        Oort oortC = startOort(serverC);
        Server serverD = startServer(transport, 0);
        Oort oortD = startOort(serverD);

        CountDownLatch latch1 = new CountDownLatch(2);
        CometJoinedListener listener1 = new CometJoinedListener(latch1);
        oortA.addCometListener(listener1);
        oortB.addCometListener(listener1);

        // Make a "cloud" connecting A and B
        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Now C wants to join the cloud, and connects to B
        CountDownLatch latch2 = new CountDownLatch(4);
        CometJoinedListener listener2 = new CometJoinedListener(latch2);
        oortA.addCometListener(listener2);
        oortB.addCometListener(listener2);
        oortC.addCometListener(listener2);
        OortComet oortCometCB = oortC.observeComet(oortB.getURL());
        Assertions.assertTrue(oortCometCB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch2.await(5, TimeUnit.SECONDS));
        OortComet oortCometBC = oortB.findComet(oortC.getURL());
        Assertions.assertTrue(oortCometBC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometAC = oortA.findComet(oortC.getURL());
        Assertions.assertTrue(oortCometAC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCA = oortC.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometCA.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Make sure C is connected to B also
        Assertions.assertEquals(2, oortA.getKnownComets().size());
        Assertions.assertEquals(2, oortB.getKnownComets().size());
        Assertions.assertEquals(2, oortC.getKnownComets().size());

        // Now also D want to join the cloud, and connects to B
        CountDownLatch latch3 = new CountDownLatch(6);
        CometJoinedListener listener3 = new CometJoinedListener(latch3);
        oortA.addCometListener(listener3);
        oortB.addCometListener(listener3);
        oortC.addCometListener(listener3);
        oortD.addCometListener(listener3);
        OortComet oortCometDB = oortD.observeComet(oortB.getURL());
        Assertions.assertTrue(oortCometDB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch3.await(5, TimeUnit.SECONDS));
        OortComet oortCometBD = oortB.findComet(oortD.getURL());
        Assertions.assertTrue(oortCometBD.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometAD = oortA.findComet(oortD.getURL());
        Assertions.assertTrue(oortCometAD.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometDA = oortD.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometDA.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometDC = oortD.findComet(oortC.getURL());
        Assertions.assertTrue(oortCometDC.waitFor(5000, BayeuxClient.State.CONNECTED));
        OortComet oortCometCD = oortC.findComet(oortD.getURL());
        Assertions.assertTrue(oortCometCD.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Make sure C is connected to B also
        Assertions.assertEquals(3, oortA.getKnownComets().size());
        Assertions.assertEquals(3, oortB.getKnownComets().size());
        Assertions.assertEquals(3, oortC.getKnownComets().size());
        Assertions.assertEquals(3, oortD.getKnownComets().size());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAckExtensionConfiguration(Transport transport) throws Exception {
        Server serverA = startServer(transport, 0);
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
        Assertions.assertEquals(1, ackExtensions);

        Server serverB = startServer(transport, 0);
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
        Assertions.assertEquals(1, ackExtensions);

        CountDownLatch latch1 = new CountDownLatch(2);
        CometJoinedListener listener1 = new CometJoinedListener(latch1);
        oortA.addCometListener(listener1);
        oortB.addCometListener(listener1);

        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch1.await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));

        ackExtensions = 0;
        for (ClientSession.Extension extension : oortCometAB.getExtensions()) {
            if (extension instanceof AckExtension) {
                ++ackExtensions;
            }
        }
        Assertions.assertEquals(1, ackExtensions);

        ackExtensions = 0;
        for (ClientSession.Extension extension : oortCometBA.getExtensions()) {
            if (extension instanceof AckExtension) {
                ++ackExtensions;
            }
        }
        Assertions.assertEquals(1, ackExtensions);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testPublishDuringCometJoined(Transport transport) throws Exception {
        Server serverA = startServer(transport, 0);
        Oort oortA = startOort(serverA);
        BayeuxServer bayeuxServerA = oortA.getBayeuxServer();

        LocalSession serviceA = bayeuxServerA.newLocalSession("test");
        serviceA.handshake();
        String channelName = "/test";
        String data = "data";
        CountDownLatch joinedLatch = new CountDownLatch(1);
        oortA.addCometListener(new Oort.CometListener() {
            @Override
            public void cometJoined(Event event) {
                bayeuxServerA.createChannelIfAbsent(channelName).getReference().publish(serviceA, data, Promise.noop());
                joinedLatch.countDown();
            }
        });
        oortA.observeChannel(channelName);

        Server serverB = startServer(transport, 0);
        Oort oortB = startOort(serverB);
        oortB.observeChannel(channelName);

        BayeuxServer bayeuxServerB = oortB.getBayeuxServer();
        CountDownLatch latch = new CountDownLatch(1);
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

        Assertions.assertTrue(joinedLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testConfigureMaxMessageSize(Transport transport) throws Exception {
        int maxMessageSize = 1024;
        Map<String, String> options = new HashMap<>();
        options.put("ws.maxMessageSize", String.valueOf(maxMessageSize));
        Server serverA = startServer(transport, 0, options);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(transport, 0, options);
        Oort oortB = startOort(serverB);

        CountDownLatch latch = new CountDownLatch(2);
        oortA.addCometListener(new CometJoinedListener(latch));
        oortB.addCometListener(new CometJoinedListener(latch));

        OortComet oortComet12 = oortA.observeComet(oortB.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));

        OortComet oortComet21 = oortB.findComet(oortA.getURL());
        Assertions.assertNotNull(oortComet21);
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));

        String channelName = "/foo";
        oortA.observeChannel(channelName);
        oortB.observeChannel(channelName);

        BayeuxClient clientA = startClient(oortA, null);
        Assertions.assertTrue(clientA.waitFor(5000, BayeuxClient.State.CONNECTED));
        BayeuxClient clientB = startClient(oortB, null);
        Assertions.assertTrue(clientB.waitFor(5000, BayeuxClient.State.CONNECTED));

        AtomicReference<CountDownLatch> messageLatch = new AtomicReference<>(new CountDownLatch(1));
        clientB.getChannel(channelName).subscribe((channel, message) -> messageLatch.get().countDown());

        // Wait a while to be sure to be subscribed
        Thread.sleep(1000);

        char[] clob = new char[maxMessageSize / 2];
        Arrays.fill(clob, 'w');
        clientA.getChannel(channelName).publish(new String(clob));
        Assertions.assertTrue(messageLatch.get().await(5, TimeUnit.SECONDS));

        // Make the message larger than allowed
        messageLatch.set(new CountDownLatch(1));
        clob = new char[maxMessageSize * 2];
        Arrays.fill(clob, 'z');
        clientA.getChannel(channelName).publish(new String(clob));
        Assertions.assertFalse(messageLatch.get().await(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHandshakeReplyFailureDoesNotDisconnectOortComet(Transport transport) throws Exception {
        Server serverA = startServer(transport, 0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(transport, 0);
        long maxNetworkDelay = 1000;
        BayeuxServer bayeuxServerB = (BayeuxServer)serverB.getAttribute(BayeuxServer.ATTRIBUTE);
        bayeuxServerB.addExtension(new BayeuxServer.Extension() {
            private final AtomicInteger replies = new AtomicInteger();

            @Override
            public boolean sendMeta(ServerSession session, ServerMessage.Mutable message) {
                if (session != null && !session.isLocalSession() &&
                        Channel.META_HANDSHAKE.equals(message.getChannel()) &&
                        message.containsKey(Message.SUCCESSFUL_FIELD)) {
                    if (replies.incrementAndGet() == 1) {
                        // Delay the reply so the client thinks it has been lost.
                        sleep(3 * maxNetworkDelay);
                    }
                }
                return true;
            }
        });
        Oort oortB = startOort(serverB);

        CountDownLatch joinLatch = new CountDownLatch(1);
        oortB.addCometListener(new CometJoinedListener(joinLatch));
        CountDownLatch leftLatch = new CountDownLatch(1);
        oortB.addCometListener(new CometLeftListener(leftLatch));

        OortComet oortCometAB = oortA.createOortComet(oortB.getURL());
        oortCometAB.setOption(ClientTransport.MAX_NETWORK_DELAY_OPTION, maxNetworkDelay);
        CountDownLatch handshakeLatch = new CountDownLatch(1);
        oortCometAB.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener() {
            private final AtomicInteger handshakes = new AtomicInteger();

            @Override
            public void onMessage(ClientSessionChannel channel, Message message) {
                // The first handshake should timeout.
                if (handshakes.incrementAndGet() == 1) {
                    if (!message.isSuccessful()) {
                        handshakeLatch.countDown();
                    }
                }
            }
        });
        oortA.connectComet(oortCometAB);

        // Wait for the first handshake reply to fail, the
        // OortComet should retry the handshake and succeed.
        sleep(2 * maxNetworkDelay);

        Assertions.assertTrue(handshakeLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(oortCometAB.isDisconnected());
        Assertions.assertTrue(joinLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(leftLatch.await(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testJoinMessageFailure(Transport transport) throws Exception {
        Server serverA = startServer(transport, 0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(transport, 0);
        BayeuxServer bayeuxServerB = (BayeuxServer)serverB.getAttribute(BayeuxServer.ATTRIBUTE);
        ServerChannel joinChannel = bayeuxServerB.createChannelIfAbsent(Oort.OORT_SERVICE_CHANNEL).getReference();
        AtomicBoolean joinMessage = new AtomicBoolean();
        joinChannel.addListener(new ServerChannel.MessageListener() {
            @Override
            public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
                return joinMessage.get();
            }
        });
        Oort oortB = startOort(serverB);

        AtomicReference<CountDownLatch> joinEventLatch = new AtomicReference<>(new CountDownLatch(1));
        oortB.addCometListener(new Oort.CometListener() {
            @Override
            public void cometJoined(Event event) {
                joinEventLatch.get().countDown();
            }
        });

        OortComet oortCometAB = oortA.createOortComet(oortB.getURL());
        CountDownLatch joinMessageFailure = new CountDownLatch(1);
        CountDownLatch joinMessageSuccess = new CountDownLatch(1);
        oortCometAB.getChannel(Oort.OORT_SERVICE_CHANNEL).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                joinMessageSuccess.countDown();
            } else {
                joinMessageFailure.countDown();
            }
        });
        oortA.connectComet(oortCometAB);

        // Must not emit the join event if the join message failed.
        Assertions.assertFalse(joinEventLatch.get().await(1, TimeUnit.SECONDS));

        // Allow the join message to succeed, the join message must be retried.
        joinEventLatch.set(new CountDownLatch(1));
        joinMessage.set(true);
        Assertions.assertTrue(joinEventLatch.get().await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHandshakeFailureDoesNotDisconnectOortComet(Transport transport) throws Exception {
        Server serverA = startServer(transport, 0);
        Oort oortA = startOort(serverA);
        Server serverB = startServer(transport, 0);
        BayeuxServer bayeuxServerB = (BayeuxServer)serverB.getAttribute(BayeuxServer.ATTRIBUTE);
        bayeuxServerB.addExtension(new BayeuxServer.Extension() {
            private final AtomicInteger handshakes = new AtomicInteger();

            @Override
            public boolean rcvMeta(ServerSession session, ServerMessage.Mutable message) {
                if (session != null && !session.isLocalSession() &&
                        Channel.META_HANDSHAKE.equals(message.getChannel())) {
                    return handshakes.incrementAndGet() > 1;
                }
                return true;
            }
        });
        Oort oortB = startOort(serverB);

        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));

        oortCometAB.disconnect();
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMessagesDroppedOnlyInOneDirection(Transport transport) throws Exception {
        long sweepPeriod = 1000;
        long maxNetworkDelay = 1000;
        long timeout = 2000;
        long maxInterval = timeout + maxNetworkDelay + 1000;
        Map<String, String> options = new HashMap<>();
        options.put(BayeuxServerImpl.SWEEP_PERIOD_OPTION, String.valueOf(sweepPeriod));
        options.put(ClientTransport.MAX_NETWORK_DELAY_OPTION, String.valueOf(maxNetworkDelay));
        options.put(AbstractServerTransport.TIMEOUT_OPTION, String.valueOf(timeout));
        options.put(AbstractServerTransport.MAX_INTERVAL_OPTION, String.valueOf(maxInterval));
        Server serverA = startServer(transport, 0, options);
        String urlA = (String)serverA.getAttribute(OortConfigServlet.OORT_URL_PARAM);
        BayeuxServerImpl bayeuxServerA = (BayeuxServerImpl)serverA.getAttribute(BayeuxServer.ATTRIBUTE);
        bayeuxServerA.setOption(Server.class.getName(), serverA);
        AtomicBoolean halfNetworkDown = new AtomicBoolean();
        Oort oortA = new Oort(bayeuxServerA, urlA) {
            @Override
            protected OortComet newOortComet(String cometURL, ClientTransport transport, ClientTransport[] otherTransports) {
                return new OortComet(this, cometURL, getScheduler(), transport, otherTransports) {
                    {
                        addTransportListener(new TransportListener() {
                            @Override
                            public void onMessages(List<Message.Mutable> messages) {
                                if (halfNetworkDown.get()) {
                                    logger.info("Network down for client receive {}", messages);
                                    messagesFailure(new Exception(), messages);
                                    messages.clear();
                                }
                            }
                        });
                    }
                };
            }

            @Override
            protected void configureOortComet(OortComet oortComet) {
                super.configureOortComet(oortComet);
                oortComet.setOption(BayeuxClient.BACKOFF_INCREMENT_OPTION, 250L);
            }
        };
        bayeuxServerA.addExtension(new HalfNetworkDownExtension(oortA, halfNetworkDown));
        oortA.start();
        oorts.add(oortA);
        Server serverB = startServer(transport, 0, options);
        Oort oortB = startOort(serverB);

        AtomicInteger joinCount = new AtomicInteger();
        AtomicReference<CountDownLatch> joinLatch = new AtomicReference<>(new CountDownLatch(2));
        Oort.CometListener joinListener = new Oort.CometListener() {
            @Override
            public void cometJoined(Event event) {
                joinCount.incrementAndGet();
                joinLatch.get().countDown();
            }
        };
        oortA.addCometListener(joinListener);
        oortB.addCometListener(joinListener);
        OortComet oortCometAB = oortA.observeComet(oortB.getURL());
        Assertions.assertTrue(oortCometAB.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(joinLatch.get().await(5, TimeUnit.SECONDS));
        OortComet oortCometBA = oortB.findComet(oortA.getURL());
        Assertions.assertTrue(oortCometBA.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Wait for the /meta/connects to be held.
        sleep(1000);

        AtomicInteger leftCount = new AtomicInteger();
        CountDownLatch leftLatch = new CountDownLatch(1);
        Oort.CometListener leftListener = new Oort.CometListener() {
            @Override
            public void cometLeft(Event event) {
                leftCount.incrementAndGet();
                leftLatch.countDown();
            }
        };
        oortA.addCometListener(leftListener);
        logger.info("HALF NETWORK DOWN");
        halfNetworkDown.set(true);

        // Wait for node2 left event on node1.
        // We need to explicitly remove the session, simulating that node1 does
        // not receive messages from node2 and therefore times out the session.
        bayeuxServerA.removeServerSession(bayeuxServerA.getSession(oortCometBA.getId()), true);
        Assertions.assertTrue(leftLatch.await(timeout + maxInterval + 2 * sweepPeriod, TimeUnit.MILLISECONDS));
        // Make sure there is only one node left event.
        sleep(1000);
        Assertions.assertEquals(1, leftCount.get());

        // Restore connectivity.
        joinCount.set(0);
        joinLatch.set(new CountDownLatch(1));
        logger.info("HALF NETWORK UP");
        halfNetworkDown.set(false);

        Assertions.assertTrue(oortCometAB.waitFor(10000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(oortCometBA.waitFor(10000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(joinLatch.get().await(10, TimeUnit.SECONDS));
        // Make sure there is only one node joined event.
        sleep(1000);
        Assertions.assertEquals(1, joinCount.get());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testProtectedOortChannels(Transport transport) throws Exception {
        Server server1 = startServer(transport, 0);
        Oort oort1 = startOort(server1);

        BayeuxClient client = startClient(oort1, null);
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        client.getChannel("/oort/*").subscribe((channel, message) -> {}, message -> {
            // Must not be able to subscribe.
            if (!message.isSuccessful()) {
                subscribeLatch.countDown();
            }
        });
        Assertions.assertTrue(subscribeLatch.await(1, TimeUnit.SECONDS));

        CountDownLatch publishLatch1 = new CountDownLatch(1);
        client.getChannel("/oort/cloud").publish("data1", message -> {
            // Must not be able to publish.
            if (!message.isSuccessful()) {
                publishLatch1.countDown();
            }
        });
        Assertions.assertTrue(publishLatch1.await(1, TimeUnit.SECONDS));

        CountDownLatch publishLatch2 = new CountDownLatch(1);
        client.getChannel("/service/oort").publish("data2", message -> {
            // Must not be able to publish.
            if (!message.isSuccessful()) {
                publishLatch2.countDown();
            }
        });
        Assertions.assertTrue(publishLatch2.await(1, TimeUnit.SECONDS));

        String broadcastChannel = "/broadcast";
        CountDownLatch allMessageLatch = new CountDownLatch(1);
        CountDownLatch allSubscribeLatch = new CountDownLatch(1);
        client.getChannel("/**").subscribe((channel, message) -> {
            String channelName = message.getChannel();
            if (channelName.startsWith("/oort") || channelName.equals(broadcastChannel)) {
                allMessageLatch.countDown();
            }
        }, message -> {
            if (message.isSuccessful()) {
                allSubscribeLatch.countDown();
            }
        });

        Assertions.assertTrue(allSubscribeLatch.await(5, TimeUnit.SECONDS));

        // Cause an Oort message to be broadcast.
        Server server2 = startServer(transport, 0);
        Oort oort2 = startOort(server2);
        oort1.observeComet(oort2.getURL());

        // Make sure it was not received.
        Assertions.assertFalse(allMessageLatch.await(1, TimeUnit.SECONDS));

        // Publish a non-Oort message, make sure it's received.
        client.getChannel(broadcastChannel).publish("hello");
        Assertions.assertTrue(allMessageLatch.await(5, TimeUnit.SECONDS));
    }

    private void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(x);
        }
    }
}
