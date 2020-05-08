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
package org.cometd.server;

import java.io.IOException;
import java.text.ParseException;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.common.JettyJSONContext;

public class JettyJSONContextServer extends JettyJSONContext<ServerMessage.Mutable> implements JSONContextServer {
    @Override
    protected ServerMessage.Mutable newRoot() {
        return new ServerMessageImpl();
    }

    @Override
    protected ServerMessage.Mutable[] newRootArray(int size) {
        return new ServerMessage.Mutable[size];
    }

    @Override
    public NonBlockingParser<Mutable> newNonBlockingParser() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean supportsNonBlockingParser() {
        return false;
    }
}
