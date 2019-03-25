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
package org.cometd.server.ext;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.cometd.bayeux.BinaryData;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.Z85;

/**
 * <p>A server extension that encodes {@code byte[]} or {@link ByteBuffer} into a {@link BinaryData}
 * object using the {@link Z85} format for outgoing messages, and decodes {@link BinaryData}
 * objects back into {@code byte[]} or {@link ByteBuffer} for incoming messages.</p>
 *
 * @see BinarySessionExtension
 */
public class BinaryExtension implements BayeuxServer.Extension {
    private final boolean decodeToByteBuffer;

    public BinaryExtension() {
        this(true);
    }

    public BinaryExtension(boolean decodeToByteBuffer) {
        this.decodeToByteBuffer = decodeToByteBuffer;
    }

    @Override
    public boolean rcv(ServerSession from, ServerMessage.Mutable message) {
        Map<String, Object> ext = message.getExt();
        if (ext != null) {
            if (ext.remove(BinaryData.EXT_NAME) != null) {
                Map<String, Object> data = message.getDataAsMap();
                BinaryData newData = new BinaryData(data);
                message.setData(newData);
                String encoded = (String)data.get(BinaryData.DATA);
                Object decoded = decodeToByteBuffer ?
                        Z85.decoder.decodeByteBuffer(encoded) :
                        Z85.decoder.decodeBytes(encoded);
                newData.put(BinaryData.DATA, decoded);
            }
        }
        return true;
    }

    @Override
    public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message) {
        Object data = message.getData();
        if (data instanceof BinaryData) {
            BinaryData binaryData = (BinaryData)data;
            Object binary = binaryData.get(BinaryData.DATA);
            String encoded;
            if (binary instanceof byte[]) {
                encoded = Z85.encoder.encodeBytes(binaryData.asBytes());
            } else if (binary instanceof ByteBuffer) {
                encoded = Z85.encoder.encodeByteBuffer(binaryData.asByteBuffer());
            } else {
                throw new IllegalArgumentException("Cannot Z85 encode " + binary);
            }
            Map<String, Object> newData = new HashMap<>(binaryData);
            newData.put(BinaryData.DATA, encoded);
            message.setData(newData);
            Map<String, Object> ext = message.getExt(true);
            ext.put(BinaryData.EXT_NAME, new HashMap<>(0));
        }
        return true;
    }
}
