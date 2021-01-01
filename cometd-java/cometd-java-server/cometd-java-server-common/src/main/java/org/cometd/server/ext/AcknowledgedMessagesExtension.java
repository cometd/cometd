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
package org.cometd.server.ext;

import java.util.Map;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.ServerSessionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Installing this extension in a {@link BayeuxServer} provides support for
 * server-to-client message acknowledgement if a client also supports it.</p>
 * <p>The main role of this extension is to install the
 * {@link AcknowledgedMessagesSessionExtension} on the {@link ServerSession}
 * instances created during successful handshakes.</p>
 */
public class AcknowledgedMessagesExtension implements Extension {
    private final Logger _logger = LoggerFactory.getLogger(getClass().getName());

    @Override
    public boolean rcvMeta(ServerSession remote, Mutable message) {
        if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
            Map<String, Object> rcvExt = message.getExt();
            boolean clientRequestedAcks = rcvExt != null && rcvExt.get("ack") == Boolean.TRUE;

            if (clientRequestedAcks && remote != null) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Enabled message acknowledgement for session {}", remote);
                }

                AcknowledgedMessagesSessionExtension extension = new AcknowledgedMessagesSessionExtension(remote);

                // Make sure that adding the extension and importing the queue is atomic.
                ServerSessionImpl session = (ServerSessionImpl)remote;
                synchronized (session.getLock()) {
                    session.addExtension(extension);
                    extension.importMessages(session);
                }
            }
        }
        return true;
    }
}
