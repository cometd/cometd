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
package org.cometd.common;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;

public class HashMapMessage extends HashMap<String, Object> implements Message.Mutable, Serializable {
    private static final long serialVersionUID = 4318697940670212190L;

    public HashMapMessage() {
    }

    public HashMapMessage(Message message) {
        putAll(message);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAdvice() {
        return (Map<String, Object>)get(ADVICE_FIELD);
    }

    @Override
    public String getChannel() {
        return (String)get(CHANNEL_FIELD);
    }

    @Override
    public ChannelId getChannelId() {
        return new ChannelId(getChannel());
    }

    @Override
    public String getClientId() {
        return (String)get(CLIENT_ID_FIELD);
    }

    @Override
    public Object getData() {
        return get(DATA_FIELD);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDataAsMap() {
        return (Map<String, Object>)get(DATA_FIELD);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getExt() {
        return (Map<String, Object>)get(EXT_FIELD);
    }

    @Override
    public String getId() {
        // Support also old-style ids of type long
        Object id = get(ID_FIELD);
        return id == null ? null : String.valueOf(id);
    }

    @Override
    public String getJSON() {
        return _jsonContext.generate(this);
    }

    @Override
    public Map<String, Object> getAdvice(boolean create) {
        Map<String, Object> advice = getAdvice();
        if (create && advice == null) {
            advice = new HashMap<>(4);
            put(ADVICE_FIELD, advice);
        }
        return advice;
    }

    @Override
    public Map<String, Object> getDataAsMap(boolean create) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = getDataAsMap();
        if (create && data == null) {
            data = new HashMap<>();
            put(DATA_FIELD, data);
        }
        return data;
    }

    @Override
    public Map<String, Object> getExt(boolean create) {
        Map<String, Object> ext = getExt();
        if (create && ext == null) {
            ext = new HashMap<>();
            put(EXT_FIELD, ext);
        }
        return ext;
    }

    @Override
    public boolean isMeta() {
        return ChannelId.isMeta(getChannel());
    }

    @Override
    public boolean isPublishReply() {
        return !isMeta() && !containsKey(DATA_FIELD);
    }

    @Override
    public boolean isSuccessful() {
        Boolean value = (Boolean)get(Message.SUCCESSFUL_FIELD);
        return value != null && value;
    }

    @Override
    public void setChannel(String channel) {
        if (channel == null) {
            remove(CHANNEL_FIELD);
        } else {
            put(CHANNEL_FIELD, channel);
        }
    }

    @Override
    public void setClientId(String clientId) {
        if (clientId == null) {
            remove(CLIENT_ID_FIELD);
        } else {
            put(CLIENT_ID_FIELD, clientId);
        }
    }

    @Override
    public void setData(Object data) {
        if (data == null) {
            remove(DATA_FIELD);
        } else {
            put(DATA_FIELD, data);
        }
    }

    @Override
    public void setId(String id) {
        if (id == null) {
            remove(ID_FIELD);
        } else {
            put(ID_FIELD, id);
        }
    }

    @Override
    public void setSuccessful(boolean successful) {
        put(SUCCESSFUL_FIELD, successful);
    }

    // The code below is a relic of a mistake in the API, but it is kept for backward compatibility.

    private static JSONContext.Client _jsonContext = new JettyJSONContextClient();
}
