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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.JettyJSONContext;
import org.eclipse.jetty.util.ajax.AsyncJSON;

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
    public AsyncParser newAsyncParser() {
        AsyncJSON asyncJSON = getAsyncJSONFactory().newAsyncJSON();
        return new AsyncJSONParser(asyncJSON) {
            @SuppressWarnings("unchecked")
            @Override
            public <R> R complete() {
                R object = super.complete();
                if (object == null) {
                    return null;
                }
                if (object instanceof List) {
                    return object;
                }
                if (object.getClass().isArray()) {
                    return (R)Arrays.asList((ServerMessage.Mutable[])object);
                }
                return (R)Collections.singletonList((ServerMessage.Mutable)object);
            }
        };
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
        return new JSONGeneratorServer();
    }

    private class JSONGeneratorServer extends JSONGenerator {
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
