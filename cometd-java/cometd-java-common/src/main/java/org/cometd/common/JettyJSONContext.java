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
package org.cometd.common;

import java.io.Reader;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.ajax.AsyncJSON;
import org.eclipse.jetty.util.ajax.JSON;

public abstract class JettyJSONContext<M extends Message.Mutable> {
    private final FieldJSON _jsonParser = new FieldJSON();
    private final FieldJSON _messageParser = new MessageJSON();
    private final FieldJSON _messagesParser = new MessagesJSON();
    private final AsyncJSON.Factory _jsonFactory = new AsyncJSONFactory();

    protected JettyJSONContext() {
    }

    public JSON getJSON() {
        return _jsonParser;
    }

    public AsyncJSON.Factory getAsyncJSONFactory() {
        return _jsonFactory;
    }

    protected abstract M newMessage();

    public List<M> parse(String json) throws ParseException {
        try {
            Object object = _messagesParser.parse(new JSON.StringSource(json));
            return adapt(object);
        } catch (Exception x) {
            throw (ParseException)new ParseException(json, -1).initCause(x);
        }
    }

    public JSONContext.AsyncParser newAsyncParser() {
        AsyncJSON asyncJSON = getAsyncJSONFactory().newAsyncJSON();
        return new AsyncJSONParser(asyncJSON);
    }

    @SuppressWarnings("unchecked")
    private List<M> adapt(Object object) {
        if (object == null) {
            return null;
        }
        if (object instanceof List list) {
            return list;
        }
        if (object.getClass().isArray()) {
            return List.of((M[])object);
        }
        return List.of((M)object);
    }

    public String generate(M message) {
        return _messageParser.toJSON(message);
    }

    public JSONContext.Parser getParser() {
        return new JSONParser();
    }

    public JSONContext.Generator getGenerator() {
        return new JSONGenerator();
    }

    public void putConvertor(String className, JSON.Convertor convertor) {
        getJSON().addConvertorFor(className, convertor);
        getAsyncJSONFactory().putConvertor(className, convertor);
    }

    private class FieldJSON extends JSON {
        // Allows for optimizations

        // Overridden for visibility
        @Override
        protected Convertor getConvertor(Class<?> forClass) {
            return super.getConvertor(forClass);
        }

        @Override
        public void addConvertor(Class<?> klass, Convertor convertor) {
            addConvertorFor(klass.getName(), convertor);
        }

        @Override
        public void addConvertorFor(String name, Convertor convertor) {
            super.addConvertorFor(name, convertor);
            getAsyncJSONFactory().putConvertor(name, convertor);
        }
    }

    private class MessageJSON extends FieldJSON {
        @Override
        protected Map<String, Object> newMap() {
            return newMessage();
        }

        @Override
        protected JSON contextFor(String field) {
            return getJSON();
        }

        @Override
        protected Convertor getConvertor(Class<?> forClass) {
            return _jsonParser.getConvertor(forClass);
        }
    }

    private class MessagesJSON extends FieldJSON {
        public MessagesJSON() {
            setArrayConverter(list -> list);
        }

        @Override
        protected Map<String, Object> newMap() {
            return newMessage();
        }

        @Override
        protected JSON contextFor(String field) {
            return getJSON();
        }

        @Override
        protected JSON contextForArray() {
            return _messageParser;
        }

        @Override
        protected Convertor getConvertor(Class<?> forClass) {
            return _messageParser.getConvertor(forClass);
        }
    }

    private class JSONParser implements JSONContext.Parser {
        @Override
        @SuppressWarnings("unchecked")
        public <R> R parse(Reader reader, Class<R> type) {
            return (R)getJSON().parse(new JSON.ReaderSource(reader));
        }
    }

    protected static class AsyncJSONParser implements JSONContext.AsyncParser {
        private final AsyncJSON asyncJSON;

        public AsyncJSONParser(AsyncJSON asyncJSON) {
            this.asyncJSON = asyncJSON;
        }

        @Override
        public void parse(ByteBuffer buffer) {
            asyncJSON.parse(buffer);
        }

        @Override
        public <R> R complete() {
            return asyncJSON.complete();
        }
    }

    protected class JSONGenerator implements JSONContext.Generator {
        @Override
        public String generate(Object object) {
            return getJSON().toJSON(object);
        }
    }

    protected class AsyncJSONFactory extends AsyncJSON.Factory {
        public AsyncJSONFactory() {
            cache("1.0");
            cache("advice");
            cache("callback-polling");
            cache("channel");
            cache("clientId");
            cache("data");
            cache("error");
            cache("ext");
            cache("id");
            cache("interval");
            cache("long-polling");
            cache("/meta/connect");
            cache("/meta/disconnect");
            cache("/meta/handshake");
            cache("/meta/subscribe");
            cache("/meta/unsubscribe");
            cache("minimumVersion");
            cache("none");
            cache("reconnect");
            cache("retry");
            cache("subscription");
            cache("successful");
            cache("timeout");
            cache("supportedConnectionTypes");
            cache("version");
            cache("websocket");
        }

        @Override
        public AsyncJSON newAsyncJSON() {
            return new AsyncJSON(this) {
                @Override
                protected Map<String, Object> newObject(Context context) {
                    if (context.depth() <= 1) {
                        return newMessage();
                    }
                    return super.newObject(context);
                }
            };
        }
    }
}
