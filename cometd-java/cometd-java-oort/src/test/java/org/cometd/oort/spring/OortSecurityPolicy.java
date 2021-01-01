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
package org.cometd.oort.spring;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.oort.Seti;
import org.cometd.server.DefaultSecurityPolicy;

public class OortSecurityPolicy extends DefaultSecurityPolicy {
    private final Seti seti;

    public OortSecurityPolicy(Seti seti) {
        this.seti = seti;
    }

    @Override
    public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
        if (session.isLocalSession()) {
            return true;
        }

        if (seti.getOort().isOortHandshake(message)) {
            return true;
        }

        if (!super.canHandshake(server, session, message)) {
            return false;
        }

        String userId = (String)message.getDataAsMap().get("userId");
        seti.associate(userId, session);

        return true;
    }
}
