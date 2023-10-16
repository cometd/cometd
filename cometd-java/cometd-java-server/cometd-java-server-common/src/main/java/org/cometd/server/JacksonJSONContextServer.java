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
import org.cometd.common.JacksonJSONContext;

public class JacksonJSONContextServer extends JacksonJSONContext<ServerMessage.Mutable, ServerMessageImpl> implements JSONContextServer {
    @Override
    protected Class<ServerMessageImpl> messageClass() {
        return ServerMessageImpl.class;
    }

    @Override
    public String generate(ServerMessage.Mutable message) {
        String json = JSONContextServer.super.generate(message);
        if (json == null) {
            json = super.generate(message);
        }
        return json;
    }

    @Override
    public Generator getGenerator() {
        return new ObjectMapperGeneratorServer();
    }

    private class ObjectMapperGeneratorServer extends ObjectMapperGenerator {
        @Override
        public String generate(Object object) {
            String json = null;
            if (object instanceof ServerMessageImpl) {
                json = ((ServerMessageImpl)object).getJSON();
            }
            if (json == null) {
                json = super.generate(object);
            }
            return json;
        }
    }
}
