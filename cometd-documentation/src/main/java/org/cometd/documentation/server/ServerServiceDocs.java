/*
 * Copyright (c) 2008-2020 the original author or authors.
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
package org.cometd.documentation.server;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractService;

@SuppressWarnings("unused")
public class ServerServiceDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::inherited[]
    public class EchoService extends AbstractService { // <1>
        public EchoService(BayeuxServer bayeuxServer) { // <2>
            super(bayeuxServer, "echo"); // <3>
            addService("/echo", "processEcho"); // <4>
        }

        public void processEcho(ServerSession remote, ServerMessage message) { // <5>
            remote.deliver(getServerSession(), "/echo", message.getData(), Promise.noop()); // <6>
        }
    }
    // end::inherited[]
}
