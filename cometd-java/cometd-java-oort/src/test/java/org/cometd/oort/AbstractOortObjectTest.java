/*
 * Copyright (c) 2008-2021 the original author or authors.
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;

public abstract class AbstractOortObjectTest extends OortTest {
    private final List<OortObject<?>> oortObjects = new ArrayList<>();
    protected Oort oort1;
    protected Oort oort2;

    protected void prepare(String serverTransport) throws Exception {
        prepare(serverTransport, new HashMap<>());
    }

    protected void prepare(String serverTransport, Map<String, String> options) throws Exception {
        Server server1 = startServer(serverTransport, 0, options);
        oort1 = startOort(server1);
        Server server2 = startServer(serverTransport, 0, options);
        oort2 = startOort(server2);

        CountDownLatch latch = new CountDownLatch(2);
        CometJoinedListener listener = new CometJoinedListener(latch);
        oort1.addCometListener(listener);
        oort2.addCometListener(listener);
        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertNotNull(oortComet21);
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));
    }

    @AfterEach
    public void dispose() throws Exception {
        for (int i = oortObjects.size() - 1; i >= 0; --i) {
            oortObjects.get(i).stop();
        }
    }

    protected <T> void startOortObjects(OortObject<T> oortObject1, OortObject<T> oortObject2) throws Exception {
        String channelName = oortObject1.getChannelName();
        CometSubscriptionListener listener1 = new CometSubscriptionListener(channelName, 1);
        oort1.getBayeuxServer().addListener(listener1);
        CometSubscriptionListener listener2 = new CometSubscriptionListener(channelName, 1);
        oort2.getBayeuxServer().addListener(listener2);
        OortObjectInitialListener<T> initialListener = new OortObjectInitialListener<>(2);
        oortObject1.addListener(initialListener);
        oortObject2.addListener(initialListener);
        oortObject1.start();
        // Wait for node1 to be subscribed on node2
        Assertions.assertTrue(listener2.await(5, TimeUnit.SECONDS));
        oortObject2.start();
        // Wait for node2 to be subscribed on node1
        Assertions.assertTrue(listener1.await(5, TimeUnit.SECONDS));
        // Wait for initialization of the oort objects on both nodes
        Assertions.assertTrue(initialListener.await(5, TimeUnit.SECONDS));
        oortObjects.add(oortObject1);
        oortObjects.add(oortObject2);
        logger.info("oort_object_1 -> {}", oortObject1.getOort().getURL());
        logger.info("oort_object_2 -> {}", oortObject2.getOort().getURL());
    }

    protected void startOortObject(OortObject<?> oortObject) throws Exception {
        oortObject.start();
        oortObjects.add(oortObject);
    }

    /**
     * A {@link BayeuxServer.SubscriptionListener} that is used to detect
     * whether nodeA is subscribed on nodeB on the given channel.
     */
    public static class CometSubscriptionListener implements BayeuxServer.SubscriptionListener {
        private final String channelName;
        private final CountDownLatch latch;

        public CometSubscriptionListener(String channelName, int parties) {
            this.channelName = channelName;
            this.latch = new CountDownLatch(parties);
        }

        @Override
        public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
            if (channelName.equals(channel.getId())) {
                latch.countDown();
            }
        }

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }

    /**
     * An {@link OortObject.Listener} that counts down a latch every time it receives an update
     * from a node that was not known before.
     *
     * @param <T>
     */
    public static class OortObjectInitialListener<T> implements OortObject.Listener<T> {
        private final CountDownLatch latch;

        public OortObjectInitialListener(int parties) {
            this.latch = new CountDownLatch(parties);
        }

        @Override
        public void onUpdated(OortObject.Info<T> oldInfo, OortObject.Info<T> newInfo) {
            if (oldInfo == null) {
                latch.countDown();
            }
        }

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }
}
