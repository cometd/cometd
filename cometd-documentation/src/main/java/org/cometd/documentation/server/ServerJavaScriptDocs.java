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
package org.cometd.documentation.server;

import java.util.Map;

import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;

@SuppressWarnings("unused")
public class ServerJavaScriptDocs {
    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::handshakeService[]
    @Service
    public class HandshakeService {
        @Listener(Channel.META_HANDSHAKE)
        public void metaHandshake(ServerSession remote, ServerMessage message) {
            Map<String, Object> credentials = (Map<String, Object>)message.get("com.acme.credentials");
            // Verify credentials.
        }
    }
    // end::handshakeService[]
}
