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
package org.cometd.server;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.JSONContext;

/**
 * <p>Server specific {@link JSONContext} that binds to {@link ServerMessage.Mutable}.</p>
 */
public interface JSONContextServer extends JSONContext<ServerMessage.Mutable> {
    @Override
    default String generate(ServerMessage.Mutable message) {
        if (message instanceof ServerMessageImpl) {
            return ((ServerMessageImpl)message).getJSON();
        }
        return null;
    }
}
