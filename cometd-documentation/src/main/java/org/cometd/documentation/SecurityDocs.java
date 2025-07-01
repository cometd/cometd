/*
 * Copyright (c) 2008 the original author or authors.
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

import java.util.Queue;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;

@SuppressWarnings("unused")
public class SecurityDocs {
    public void queueMaxed() {
        BayeuxServer bayeuxServer = new BayeuxServerImpl();
        // tag::queueMaxed[]
        // Configure CometDServlet init-param maxQueue=128,
        // or CometDHandler.setOptions(Map.of("maxQueue", "128"))

        // Add a SessionListener to be notified every time a new
        // ServerSession is created, to add a QueueMaxedListener that
        // will be notified when the ServerSession queue size exceeds 128.
        bayeuxServer.addListener(new BayeuxServer.SessionListener() {
            @Override
            public void sessionAdded(ServerSession serverSession, ServerMessage serverMessage) {
                // Add a QueueMaxedListener that will be
                // notified when the queue size exceeds 128.
                serverSession.addListener(new ServerSession.QueueMaxedListener() {
                    @Override
                    public boolean queueMaxed(ServerSession session, Queue<ServerMessage> queue, ServerSession sender, ServerMessage message) {
                        // When the queue overflows, clear and disconnect.
                        queue.clear();
                        session.disconnect();
                        return false;
                    }
                });
            }
        });
        // end::queueMaxed[]
    }
}
