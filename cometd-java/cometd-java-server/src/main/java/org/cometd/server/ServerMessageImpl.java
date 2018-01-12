/*
 * Copyright (c) 2008-2018 the original author or authors.
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

import java.nio.charset.StandardCharsets;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.common.HashMapMessage;
import org.cometd.common.JSONContext;

public class ServerMessageImpl extends HashMapMessage implements ServerMessage.Mutable {
    private static final long serialVersionUID = 6412048662640296067L;

    private transient ServerMessage.Mutable _associated;
    private boolean _lazy = false;
    private String _json;
    private transient byte[] _jsonBytes;

    @Override
    public ServerMessage.Mutable getAssociated() {
        return _associated;
    }

    @Override
    public void setAssociated(ServerMessage.Mutable associated) {
        _associated = associated;
    }

    @Override
    public boolean isLazy() {
        return _lazy;
    }

    @Override
    public void setLazy(boolean lazy) {
        _lazy = lazy;
    }

    protected void freeze(String json) {
        assert _json == null;
        _json = json;
        _jsonBytes = json.getBytes(StandardCharsets.UTF_8);
    }

    protected boolean isFrozen() {
        return _json != null;
    }

    @Override
    public String getJSON() {
        if (_json == null) {
            return _jsonContext.generate(this);
        }
        return _json;
    }

    public byte[] getJSONBytes() {
        return _jsonBytes;
    }

    @Override
    public Object getData() {
        Object data = super.getData();
        if (isFrozen() && data instanceof Map) {
            return Collections.unmodifiableMap((Map<String, Object>)data);
        }
        return data;
    }

    @Override
    public Object put(String key, Object value) {
        if (isFrozen()) {
            throw new UnsupportedOperationException();
        }
        return super.put(key, value);
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        if (isFrozen()) {
            return new ImmutableEntrySet(super.entrySet());
        }
        return super.entrySet();
    }

    @Override
    public Map<String, Object> getDataAsMap() {
        Map<String, Object> data = super.getDataAsMap();
        if (isFrozen() && data != null) {
            return Collections.unmodifiableMap(data);
        }
        return data;
    }

    @Override
    public Map<String, Object> getExt() {
        Map<String, Object> ext = super.getExt();
        if (isFrozen() && ext != null) {
            return Collections.unmodifiableMap(ext);
        }
        return ext;
    }

    @Override
    public Map<String, Object> getAdvice() {
        Map<String, Object> advice = super.getAdvice();
        if (isFrozen() && advice != null) {
            return Collections.unmodifiableMap(advice);
        }
        return advice;
    }

    private static class ImmutableEntrySet extends AbstractSet<Map.Entry<String, Object>> {
        private final Set<Map.Entry<String, Object>> delegate;

        private ImmutableEntrySet(Set<Map.Entry<String, Object>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Iterator<Map.Entry<String, Object>> iterator() {
            return new ImmutableEntryIterator(delegate.iterator());
        }

        @Override
        public int size() {
            return delegate.size();
        }

        private static class ImmutableEntryIterator implements Iterator<Map.Entry<String, Object>> {
            private final Iterator<Map.Entry<String, Object>> delegate;

            private ImmutableEntryIterator(Iterator<Map.Entry<String, Object>> delegate) {
                this.delegate = delegate;
            }

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public Map.Entry<String, Object> next() {
                return new ImmutableEntry(delegate.next());
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

            private static class ImmutableEntry implements Map.Entry<String, Object> {
                private final Map.Entry<String, Object> delegate;

                private ImmutableEntry(Map.Entry<String, Object> delegate) {
                    this.delegate = delegate;
                }

                @Override
                public String getKey() {
                    return delegate.getKey();
                }

                @Override
                public Object getValue() {
                    return delegate.getValue();
                }

                @Override
                public Object setValue(Object value) {
                    throw new UnsupportedOperationException();
                }
            }
        }
    }

    // The code below is a relic of a mistake in the API, but it is kept for backward compatibility

    private static JSONContext.Server _jsonContext = new JettyJSONContextServer();
}
