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
package org.cometd.bayeux;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>Reified representation of binary data chunk contained in a message.</p>
 * <p>BinaryData is composed of a {@link #getMetaData() metaData map}
 * that contains application information about the binary chunk (such as
 * a file name, the mime type, or the chunk number), of the binary chunk
 * itself (either in {@link #asBytes() byte[]} or
 * {@link #asByteBuffer() ByteBuffer} format), and of the boolean
 * {@link #isLast() last flag} indicating whether it is the last chunk.</p>
 */
public class BinaryData extends HashMap<String, Object> {
    public static final String EXT_NAME = "binary";
    public static final String META = "meta";
    public static final String DATA = "data";
    public static final String LAST = "last";

    public BinaryData(ByteBuffer byteBuffer, boolean last, Map<String, Object> metaData) {
        super(4);
        put(DATA, byteBuffer);
        put(LAST, last);
        if (metaData != null) {
            put(META, metaData);
        }
    }

    public BinaryData(byte[] bytes, boolean last, Map<String, Object> metaData) {
        super(4);
        put(DATA, bytes);
        put(LAST, last);
        if (metaData != null) {
            put(META, metaData);
        }
    }

    public BinaryData(Map<? extends String, ?> map) {
        super(map);
    }

    /**
     * @return the binary chunk as {@code ByteBuffer}
     */
    public ByteBuffer asByteBuffer() {
        Object data = get(DATA);
        if (data instanceof ByteBuffer) {
            return ((ByteBuffer)data).slice();
        } else if (data instanceof byte[]) {
            return ByteBuffer.wrap((byte[])data);
        } else {
            throw new IllegalArgumentException("Cannot convert to ByteBuffer: " + data);
        }
    }

    /**
     * @return the binary chunk as {@code byte[]}
     */
    public byte[] asBytes() {
        Object data = get(DATA);
        if (data instanceof ByteBuffer) {
            ByteBuffer buffer = ((ByteBuffer)data).slice();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } else if (data instanceof byte[]) {
            return (byte[])data;
        } else {
            throw new IllegalArgumentException("Cannot convert to byte[]: " + data);
        }
    }

    /**
     * @return whether the binary chunk is the last
     */
    public boolean isLast() {
        return (Boolean)get(LAST);
    }

    /**
     * @return the meta data associated with the binary chunk
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMetaData() {
        return (Map<String, Object>)get(META);
    }
}
