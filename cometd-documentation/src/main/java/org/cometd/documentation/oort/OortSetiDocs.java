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
package org.cometd.documentation.oort;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;

import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.oort.Oort;
import org.cometd.oort.OortComet;
import org.cometd.oort.Seti;
import org.cometd.server.DefaultSecurityPolicy;

@SuppressWarnings("unused")
public class OortSetiDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::policy[]
    public class MySecurityPolicy extends DefaultSecurityPolicy {
        private final Seti seti;

        public MySecurityPolicy(Seti seti) {
            this.seti = seti;
        }

        @Override
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise) {
            if (session.isLocalSession()) {
                promise.succeed(true);
                return;
            }

            // Authenticate.
            String userId = performAuthentication(session, message);
            if (userId == null) {
                promise.succeed(false);
                return;
            }

            // Associate
            seti.associate(userId, session);

            promise.succeed(true);
        }
    }
    // end::policy[]

    private String performAuthentication(ServerSession session, ServerMessage message) {
        return null;
    }

    public void listener(Seti seti) {
        // tag::listener[]
        seti.addPresenceListener(new Seti.PresenceListener() {
            @Override
            public void presenceAdded(Event event) {
                System.out.printf("User ID %s is now present in node %s%n", event.getUserId(), event.getOortURL());
            }

            @Override
            public void presenceRemoved(Event event) {
                System.out.printf("User ID %s is now absent in node %s%n", event.getUserId(), event.getOortURL());
            }
        });
        // end::listener[]
    }

    public void publish(Seti seti) {
        // tag::publish[]
        seti.addPresenceListener(new Seti.PresenceListener() {
            @Override
            public void presenceAdded(Event event) {
                Oort oort = seti.getOort();
                String oortURL = event.getOortURL();
                OortComet oortComet = oort.getComet(oortURL);

                Map<String, Object> data = new HashMap<>();
                data.put("action", "sync_request");
                data.put("userId", event.getUserId());

                oortComet.getChannel("/service/sync").publish(data);
            }
        });
        // end::publish[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::forward[]
    @Service("seti_forwarder")
    public class SetiForwarder {
        @Inject
        private Seti seti;

        @Listener("/service/forward")
        public void forward(ServerSession session, ServerMessage message) {
            Map<String,Object> data = message.getDataAsMap();
            String targetUserId = (String)data.get("targetUserId");
            seti.sendMessage(targetUserId, message.getChannel(), data);
        }
    }
    // end::forward[]
}
