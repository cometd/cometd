/*
 * Copyright (c) 2008-2016 the original author or authors.
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

    @SuppressWarnings("unchecked")
    public Map<String, Object> getAdvice() {
        return (Map<String, Object>)get(ADVICE_FIELD);
    }

    public String getChannel() {
        return (String)get(CHANNEL_FIELD);
    }

    public ChannelId getChannelId() {
        return new ChannelId(getChannel());
    }

    public String getClientId() {
        return (String)get(CLIENT_ID_FIELD);
    }

    public Object getData() {
        return get(DATA_FIELD);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDataAsMap() {
        return (Map<String, Object>)get(DATA_FIELD);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getExt() {
        return (Map<String, Object>)get(EXT_FIELD);
    }

    public String getId() {
        // Support also old-style ids of type long
        Object id = get(ID_FIELD);
        return id == null ? null : String.valueOf(id);
    }

    public String getJSON() {
        return _jsonContext.generate(this);
    }

    public Map<String, Object> getAdvice(boolean create) {
        Map<String, Object> advice = getAdvice();
        if (create && advice == null) {
            advice = new HashMap<>(4);
            put(ADVICE_FIELD, advice);
        }
        return advice;
    }

    public Map<String, Object> getDataAsMap(boolean create) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = getDataAsMap();
        if (create && data == null) {
            data = new HashMap<>();
            put(DATA_FIELD, data);
        }
        return data;
    }

    public Map<String, Object> getExt(boolean create) {
        Map<String, Object> ext = getExt();
        if (create && ext == null) {
            ext = new HashMap<>();
            put(EXT_FIELD, ext);
        }
        return ext;
    }

    public boolean isMeta() {
        return ChannelId.isMeta(getChannel());
    }

    public boolean isPublishReply() {
        return !isMeta() && !containsKey(DATA_FIELD);
    }

    public boolean isSuccessful() {
        Boolean value = (Boolean)get(Message.SUCCESSFUL_FIELD);
        return value != null && value;
    }

    public void setChannel(String channel) {
        if (channel == null) {
            remove(CHANNEL_FIELD);
        } else {
            put(CHANNEL_FIELD, channel);
        }
    }

    public void setClientId(String clientId) {
        if (clientId == null) {
            remove(CLIENT_ID_FIELD);
        } else {
            put(CLIENT_ID_FIELD, clientId);
        }
    }

    public void setData(Object data) {
        if (data == null) {
            remove(DATA_FIELD);
        } else {
            put(DATA_FIELD, data);
        }
    }

    public void setId(String id) {
        if (id == null) {
            remove(ID_FIELD);
        } else {
            put(ID_FIELD, id);
        }
    }

    public void setSuccessful(boolean successful) {
        put(SUCCESSFUL_FIELD, successful);
    }

    // The code below is a relic of a mistake in the API, but it is kept for backward compatibility.

    private static JSONContext.Client _jsonContext = new JettyJSONContextClient();
}
