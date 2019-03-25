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
package org.cometd.bayeux.server;

import org.cometd.bayeux.client.ClientSession;

/**
 * <p>A {@link LocalSession} is a {@link ClientSession} within the server.</p>
 * <p>Unlike a {@link ServerSession} that represents a remote client on the server,
 * a {@link LocalSession} is a <em>new</em> client, that is not remote and hence
 * local to the server, that lives in the server.</p>
 * <p>A {@link LocalSession} has an associated {@link ServerSession} and both share
 * the same clientId, but have distinct sets of listeners, batching state, etc.</p>
 */
public interface LocalSession extends ClientSession {
    /**
     * @return the associated {@link ServerSession}
     */
    ServerSession getServerSession();
}
