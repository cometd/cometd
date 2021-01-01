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
package org.cometd.client.ext;

import java.util.Map;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message.Mutable;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSession.Extension;

/**
 * <p>This client-side extension enables the client to acknowledge to the server
 * the messages that the client has received.</p>
 * <p>For the acknowledgement to work, the server must be configured with the
 * correspondent server-side ack extension. If both client and server support
 * the ack extension, then the ack functionality will take place automatically.
 * By enabling this extension, all messages arriving from the server will arrive
 * via the long poll, so the comet communication will be slightly chattier.
 * The fact that all messages will return via long poll means also that the
 * messages will arrive with total order, which is not guaranteed if messages
 * can arrive via both long poll and normal response.
 * Messages are not acknowledged one by one, but instead a group of messages is
 * acknowledged when long poll returns.</p>
 */
public class AckExtension extends Extension.Adapter {
    public static final String ACK_FIELD = "ack";

    private boolean _serverSupportsAcks;
    private long _batch;

    @Override
    public boolean rcvMeta(ClientSession session, Mutable message) {
        if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
            Map<String, Object> ext = message.getExt(false);
            if (ext != null) {
                Object field = ext.get(ACK_FIELD);
                if (field instanceof Map) {
                    // New format.
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ack = (Map<String, Object>)field;
                    _serverSupportsAcks = Boolean.TRUE.equals(ack.get("enabled"));
                    Object batch = ack.get("batch");
                    if (batch instanceof Number) {
                        _batch = ((Number)batch).longValue();
                    }
                } else {
                    // Old format.
                    _serverSupportsAcks = Boolean.TRUE.equals(field);
                }
            }
        } else if (Channel.META_CONNECT.equals(message.getChannel()) && message.isSuccessful() && _serverSupportsAcks) {
            Map<String, Object> ext = message.getExt(false);
            if (ext != null) {
                Object ack = ext.get(ACK_FIELD);
                if (ack instanceof Number) {
                    _batch = ((Number)ack).longValue();
                }
            }
        }
        return true;
    }

    @Override
    public boolean sendMeta(ClientSession session, Mutable message) {
        if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
            _serverSupportsAcks = false;
            _batch = 0;
            message.getExt(true).put(ACK_FIELD, Boolean.TRUE);
        } else if (Channel.META_CONNECT.equals(message.getChannel())) {
            if (_serverSupportsAcks) {
                message.getExt(true).put(ACK_FIELD, _batch);
            }
        }
        return true;
    }
}
