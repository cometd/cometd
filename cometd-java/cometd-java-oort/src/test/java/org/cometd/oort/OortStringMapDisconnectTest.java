/*
 * Copyright (c) 2008-2019 the original author or authors.
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.server.AbstractService;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OortStringMapDisconnectTest extends OortTest {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final List<Seti> setis = new ArrayList<>();
    private final List<OortStringMap<String>> oortStringMaps = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private HttpClient httpClient;

    public OortStringMapDisconnectTest(String serverTransport) {
        super(serverTransport);
    }

    @Before
    public void prepare() throws Exception {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        httpClient = new HttpClient();
        httpClient.setExecutor(clientThreads);
        httpClient.setMaxConnectionsPerDestination(65536);
        httpClient.start();
    }

    @After
    public void dispose() throws Exception {
        for (OortStringMap<String> oortStringMap : oortStringMaps) {
            oortStringMap.stop();
        }
        if (httpClient != null) {
            httpClient.stop();
        }
        scheduler.shutdown();
    }

    @Test
    public void testMassiveDisconnect() throws Exception {
        int nodes = 4;
        int usersPerNode = 500;
        int totalUsers = nodes * usersPerNode;
        // One event in a node is replicated to other "nodes" nodes.
        int totalEvents = nodes * totalUsers;
        prepareNodes(nodes);

        // Register a service so that when a user logs in,
        // it is recorded in the users OortStringMap.
        for (int i = 0; i < nodes; i++) {
            Seti seti = setis.get(i);
            OortStringMap<String> oortStringMap = oortStringMaps.get(i);
            new UserService(seti, oortStringMap);
        }

        final CountDownLatch presenceLatch = new CountDownLatch(totalEvents);
        Seti.PresenceListener presenceListener = new Seti.PresenceListener.Adapter() {
            @Override
            public void presenceRemoved(Event event) {
                presenceLatch.countDown();
            }
        };
        for (int i = 0; i < nodes; i++) {
            Seti seti = setis.get(i);
            seti.addPresenceListener(presenceListener);
        }

        final CountDownLatch putLatch = new CountDownLatch(totalEvents);
        final CountDownLatch removedLatch = new CountDownLatch(totalEvents);
        for (final OortStringMap<String> oortStringMap : oortStringMaps) {
            OortMap.EntryListener<String, String> listener = new OortMap.EntryListener.Adapter<String, String>() {
                @Override
                public void onPut(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                    putLatch.countDown();
                }

                @Override
                public void onRemoved(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                    removedLatch.countDown();
                }
            };
            oortStringMap.addListener(new OortMap.DeltaListener<>(oortStringMap));
            oortStringMap.addEntryListener(listener);
        }

        // Login users.
        List<List<BayeuxClient>> clients = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            Oort oort = oorts.get(i);
            List<BayeuxClient> clientsPerNode = new ArrayList<>();
            clients.add(clientsPerNode);
            for (int j = 0; j < usersPerNode; j++) {
                BayeuxClient client = new BayeuxClient(oort.getURL(), scheduler, new LongPollingTransport(null, httpClient));
                clientsPerNode.add(client);
                client.handshake();
                Assert.assertTrue(client.waitFor(15000, BayeuxClient.State.CONNECTED));
                String userName = "user_" + i + "_" + j;
                client.getChannel(UserService.LOGIN_CHANNEL).publish(userName);
            }
        }

        long await = Math.max(1000, totalEvents * 10L);
        Assert.assertTrue(putLatch.await(await, TimeUnit.MILLISECONDS));

        Thread.sleep(1000);

        // Disconnect clients.
        for (List<BayeuxClient> clientsPerNode : clients) {
            for (BayeuxClient client : clientsPerNode) {
                client.disconnect();
            }
        }

        Assert.assertTrue(removedLatch.await(await, TimeUnit.MILLISECONDS));
        for (OortStringMap<String> oortStringMap : oortStringMaps) {
            ConcurrentMap<String, String> merge = oortStringMap.merge(OortObjectMergers.concurrentMapUnion());
            Assert.assertThat(merge.toString(), merge.size(), Matchers.equalTo(0));
        }

        Assert.assertTrue(presenceLatch.await(await, TimeUnit.MILLISECONDS));
        for (Seti seti : setis) {
            Set<String> userIds = seti.getUserIds();
            Assert.assertThat(userIds.toString(), userIds.size(), Matchers.equalTo(0));
        }
    }

    private void prepareNodes(int nodes) throws Exception {
        int edges = nodes * (nodes - 1);
        // Create the Oorts.
        final CountDownLatch joinLatch = new CountDownLatch(edges);
        Oort.CometListener joinListener = new Oort.CometListener.Adapter() {
            @Override
            public void cometJoined(Event event) {
                joinLatch.countDown();
            }
        };
        Map<String, String> options = new HashMap<>();
        options.put("ws.maxMessageSize", String.valueOf(1024 * 1024));
        for (int i = 0; i < nodes; i++) {
            Server server = startServer(0, options);
            Oort oort = startOort(server);
            oort.addCometListener(joinListener);
        }
        // Connect the Oorts.
        Oort oort1 = oorts.get(0);
        for (int i = 1; i < oorts.size(); i++) {
            Oort oort = oorts.get(i);
            OortComet oortComet1X = oort1.observeComet(oort.getURL());
            Assert.assertTrue(oortComet1X.waitFor(5000, BayeuxClient.State.CONNECTED));
            OortComet oortCometX1 = oort.findComet(oort1.getURL());
            Assert.assertTrue(oortCometX1.waitFor(5000, BayeuxClient.State.CONNECTED));
        }
        Assert.assertTrue(joinLatch.await(nodes * 2, TimeUnit.SECONDS));
        Thread.sleep(1000);
        logger.debug("Oorts joined");

        // Start the Setis.
        final CountDownLatch setiLatch = new CountDownLatch(edges);
        for (final Oort oort : oorts) {
            Seti seti = new Seti(oort) {
                @Override
                protected void receiveRemotePresence(Map<String, Object> presence) {
                    setiLatch.countDown();
                    super.receiveRemotePresence(presence);
                }
            };
            setis.add(seti);
            seti.start();
        }
        Assert.assertTrue(setiLatch.await(5, TimeUnit.SECONDS));
        logger.debug("Setis started");

        // Start the OortStringMaps.
        String name = "users";
        OortObject.Factory<ConcurrentMap<String, String>> factory = OortObjectFactories.forConcurrentMap();
        final CountDownLatch mapLatch = new CountDownLatch(edges);
        for (Oort oort : oorts) {
            OortStringMap<String> users = new OortStringMap<>(oort, name, factory);
            oortStringMaps.add(users);
            users.addListener(new OortObject.Listener.Adapter<ConcurrentMap<String, String>>() {
                @Override
                public void onUpdated(OortObject.Info<ConcurrentMap<String, String>> oldInfo, OortObject.Info<ConcurrentMap<String, String>> newInfo) {
                    if (oldInfo == null) {
                        mapLatch.countDown();
                    }
                }
            });
            users.start();
        }
        Assert.assertTrue(mapLatch.await(5, TimeUnit.SECONDS));
        logger.debug("OortObjects started");

        // Verify that the OortStringMaps are setup correctly.
        final String setupKey = "setup";
        final CountDownLatch setupLatch = new CountDownLatch(2 * nodes);
        OortMap.EntryListener<String, String> setupListener = new OortMap.EntryListener.Adapter<String, String>() {
            @Override
            public void onPut(OortObject.Info info, OortMap.Entry entry) {
                if (entry.getKey().equals(setupKey)) {
                    setupLatch.countDown();
                }
            }

            @Override
            public void onRemoved(OortObject.Info<ConcurrentMap<String, String>> info, OortMap.Entry<String, String> entry) {
                if (entry.getKey().equals(setupKey)) {
                    setupLatch.countDown();
                }
            }
        };
        for (OortStringMap<String> oortStringMap : oortStringMaps) {
            // It is possible that the put() and remove() result
            // in a whole Map change, rather than individual
            // changes, so the delta listener is required.
            oortStringMap.addListener(new OortMap.DeltaListener<>(oortStringMap));
            oortStringMap.addEntryListener(setupListener);
        }
        OortStringMap<String> oortStringMap1 = oortStringMaps.get(0);
        OortObject.Result.Deferred<String> putAction = new OortObject.Result.Deferred<>();
        oortStringMap1.putAndShare(setupKey, setupKey, putAction);
        Assert.assertNull(putAction.get(5, TimeUnit.SECONDS));
        logger.debug("Setup putAndShare() complete");

        OortObject.Result.Deferred<String> removeAction = new OortObject.Result.Deferred<>();
        oortStringMap1.removeAndShare(setupKey, removeAction);
        Assert.assertNotNull(removeAction.get(5, TimeUnit.SECONDS));
        logger.debug("Setup removeAndShare() complete");

        Assert.assertTrue(setupLatch.await(5, TimeUnit.SECONDS));

        for (OortStringMap<String> oortStringMap : oortStringMaps) {
            oortStringMap.removeListeners();
            oortStringMap.removeEntryListeners();
        }
    }

    public static class UserService extends AbstractService implements ServerSession.RemoveListener {
        private static final String LOGIN_CHANNEL = "/service/login";
        private final Seti seti;
        private final OortStringMap<String> oortStringMap;

        public UserService(Seti seti, OortStringMap<String> oortStringMap) {
            super(seti.getOort().getBayeuxServer(), "userService");
            this.seti = seti;
            this.oortStringMap = oortStringMap;
            addService(LOGIN_CHANNEL, "login");
        }

        public void login(ServerSession session, ServerMessage message) {
            session.addListener(this);
            String userName = (String)message.getData();
            session.setAttribute("userName", userName);
            seti.associate(userName, session);
            oortStringMap.putAndShare(userName, userName, new OortObject.Result.Deferred<String>());
        }

        @Override
        public void removed(ServerSession session, boolean timeout) {
            String userName = (String)session.getAttribute("userName");
            seti.disassociate(userName, session);
            oortStringMap.removeAndShare(userName, new OortObject.Result.Deferred<String>());
        }
    }
}
