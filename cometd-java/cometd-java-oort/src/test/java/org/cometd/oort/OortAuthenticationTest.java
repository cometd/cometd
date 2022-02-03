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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.common.HashMapMessage;
import org.cometd.server.DefaultSecurityPolicy;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class OortAuthenticationTest extends OortTest {
    @ParameterizedTest
    @MethodSource("transports")
    public void testAuthenticationWithSecurityPolicy(String serverTransport) throws Exception {
        Server server1 = startServer(serverTransport, 0);
        Oort oort1 = startOort(server1);
        oort1.setSecret("test_secret");
        oort1.getBayeuxServer().setSecurityPolicy(new TestSecurityPolicy(oort1));
        Server server2 = startServer(serverTransport, 0);
        Oort oort2 = startOort(server2);
        oort2.setSecret(oort1.getSecret());
        oort2.getBayeuxServer().setSecurityPolicy(new TestSecurityPolicy(oort2));

        CountDownLatch latch = new CountDownLatch(1);
        oort2.addCometListener(new CometJoinedListener(latch));

        OortComet oortComet12 = oort1.observeComet(oort2.getURL());
        Assertions.assertTrue(oortComet12.waitFor(5000, BayeuxClient.State.CONNECTED));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        OortComet oortComet21 = oort2.findComet(oort1.getURL());
        Assertions.assertTrue(oortComet21.waitFor(5000, BayeuxClient.State.CONNECTED));

        // Test that a valid remote client can connect
        Message.Mutable authFields = new HashMapMessage();
        authFields.getExt(true).put(TestSecurityPolicy.TOKEN_FIELD, "something");
        BayeuxClient client1 = startClient(oort1, authFields);
        Assertions.assertTrue(client1.waitFor(5000, BayeuxClient.State.CONNECTED));
        // Wait for long poll to be established
        Thread.sleep(1000);
        Assertions.assertTrue(client1.disconnect(5000));

        // An invalid client may not connect
        BayeuxClient client2 = startClient(oort1, null);
        Assertions.assertTrue(client2.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // Wait for the /meta/connect to return.
        Thread.sleep(1000);

        // A client that forges an Oort comet authentication may not connect
        Message.Mutable forgedAuthFields = new HashMapMessage();
        Map<String, Object> ext = forgedAuthFields.getExt(true);
        Map<String, Object> oortExt = new HashMap<>();
        ext.put(Oort.EXT_OORT_FIELD, oortExt);
        oortExt.put(Oort.EXT_OORT_URL_FIELD, oort1.getURL());
        oortExt.put(Oort.EXT_OORT_SECRET_FIELD, "anything");
        oortExt.put(Oort.EXT_COMET_URL_FIELD, oort2.getURL());
        BayeuxClient client3 = startClient(oort1, forgedAuthFields);
        Assertions.assertTrue(client3.waitFor(5000, BayeuxClient.State.DISCONNECTED));
    }

    private static class TestSecurityPolicy extends DefaultSecurityPolicy {
        private static final String TOKEN_FIELD = "token";
        private final Oort oort;

        private TestSecurityPolicy(Oort oort) {
            this.oort = oort;
        }

        @Override
        public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
            if (session.isLocalSession()) {
                return true;
            }
            if (oort.isOortHandshake(message)) {
                return true;
            }
            Map<String, Object> ext = message.getExt();
            return ext != null && ext.get(TOKEN_FIELD) != null;
        }
    }
}
