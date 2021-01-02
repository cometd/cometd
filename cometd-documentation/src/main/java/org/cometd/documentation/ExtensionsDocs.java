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
package org.cometd.documentation;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.client.BayeuxClient;
import org.cometd.server.DefaultSecurityPolicy;
import org.cometd.server.ext.AcknowledgedMessagesExtension;
import org.cometd.server.ext.ActivityExtension;

@SuppressWarnings("unused")
public class ExtensionsDocs {
    public static void acknowledgment(BayeuxServer bayeuxServer) {
        // tag::ack[]
        bayeuxServer.addExtension(new AcknowledgedMessagesExtension());
        // end::ack[]
    }

    public static void activityClient(BayeuxServer bayeuxServer) {
        // tag::activityClient[]
        bayeuxServer.addExtension(new ActivityExtension(ActivityExtension.Activity.CLIENT, 15 * 60 * 1000L));
        // end::activityClient[]
    }

    public static void activityClientServer(BayeuxServer bayeuxServer) {
        // tag::activityClientServer[]
        bayeuxServer.addExtension(new ActivityExtension(ActivityExtension.Activity.CLIENT_SERVER, 15 * 60 * 1000L));
        // end::activityClientServer[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::activityPolicy[]
    public class MyPolicy extends DefaultSecurityPolicy {
        @Override
        public void canHandshake(BayeuxServer server, ServerSession session, ServerMessage message, Promise<Boolean> promise) {
            if (!isAdminUser(session, message)) {
                session.addExtension(new ActivityExtension.SessionExtension(ActivityExtension.Activity.CLIENT, 10 * 60 * 1000L));
            }
            promise.succeed(true);
        }
    }
    // end::activityPolicy[]

    private boolean isAdminUser(ServerSession session, ServerMessage message) {
        return false;
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::activityExtension[]
    public class MyExtension implements BayeuxServer.Extension {
        @Override
        public boolean sendMeta(ServerSession session, ServerMessage.Mutable message) {
            if (Channel.META_HANDSHAKE.equals(message.getChannel()) && message.isSuccessful()) {
                if (!isAdminUser(session, message)) {
                    session.addExtension(new ActivityExtension.SessionExtension(ActivityExtension.Activity.CLIENT, 10 * 60 * 1000L));
                }
            }
            return true;
        }
    }
    // end::activityExtension[]

    public static void binaryServer(BayeuxServer bayeuxServer) {
        // tag::binaryServer[]
        bayeuxServer.addExtension(new org.cometd.server.ext.BinaryExtension());
        // end::binaryServer[]
    }

    public static void binaryClient(BayeuxClient bayeuxClient) {
        // tag::bayeuxClient[]
        bayeuxClient.addExtension(new org.cometd.client.ext.BinaryExtension());
        // end::bayeuxClient[]
    }

    public static void timestamp(BayeuxServer bayeuxServer) {
        // tag::timestamp[]
        bayeuxServer.addExtension(new org.cometd.server.ext.TimestampExtension());
        // end::timestamp[]
    }

    public static void timesync(BayeuxServer bayeuxServer) {
        // tag::timesync[]
        bayeuxServer.addExtension(new org.cometd.server.ext.TimesyncExtension());
        // end::timesync[]
    }
}
