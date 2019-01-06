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
package org.cometd.common;

import java.nio.ByteBuffer;

/**
 * <p>An implementation of Z85, a format for representing binary data
 * as printable text defined at https://rfc.zeromq.org/spec:32/Z85/.</p>
 */
public class Z85 {
    private static char[] encodeTable = new char[]{
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j',
            'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't',
            'u', 'v', 'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D',
            'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N',
            'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
            'Y', 'Z', '.', '-', ':', '+', '=', '^', '!', '/',
            '*', '?', '&', '<', '>', '(', ')', '[', ']', '{',
            '}', '@', '%', '$', '#'
    };
    private static final int[] decodeTable = new int[]{
            0x00, 0x44, 0x00, 0x54, 0x53, 0x52, 0x48, 0x00,
            0x4B, 0x4C, 0x46, 0x41, 0x00, 0x3F, 0x3E, 0x45,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x40, 0x00, 0x49, 0x42, 0x4A, 0x47,
            0x51, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A,
            0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32,
            0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A,
            0x3B, 0x3C, 0x3D, 0x4D, 0x00, 0x4E, 0x43, 0x00,
            0x00, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
            0x21, 0x22, 0x23, 0x4F, 0x00, 0x50, 0x00, 0x00
    };
    public static Encoder encoder = new Encoder();
    public static Decoder decoder = new Decoder();

    private Z85() {
    }

    public static class Encoder {
        public String encodeBytes(byte[] bytes) {
            int length = bytes.length;
            int remainder = length % 4;
            int padding = 4 - (remainder == 0 ? 4 : remainder);
            StringBuilder result = new StringBuilder();
            long value = 0;
            for (int i = 0; i < length + padding; ++i) {
                boolean isPadding = i >= length;
                value = value * 256 + (isPadding ? 0 : bytes[i] & 0xFF);
                if ((i + 1) % 4 == 0) {
                    int divisor = 85 * 85 * 85 * 85;
                    for (int j = 5; j > 0; --j) {
                        if (!isPadding || j > padding) {
                            int code = (int)((value / divisor) % 85);
                            result.append(encodeTable[code]);
                        }
                        divisor /= 85;
                    }
                    value = 0;
                }
            }
            return result.toString();
        }

        public String encodeByteBuffer(ByteBuffer buffer) {
            // TODO: avoid copy.
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return encodeBytes(bytes);
        }
    }

    public static class Decoder {
        public byte[] decodeBytes(String string) {
            int remainder = string.length() % 5;
            int padding = 5 - (remainder == 0 ? 5 : remainder);
            for (int p = 0; p < padding; ++p) {
                string += encodeTable[encodeTable.length - 1];
            }
            int length = string.length();
            byte[] bytes = new byte[(length * 4 / 5) - padding];
            long value = 0;
            int index = 0;
            for (int i = 0; i < length; ++i) {
                int code = string.charAt(i) - 32;
                value = value * 85 + decodeTable[code];
                if ((i + 1) % 5 == 0) {
                    int divisor = 256 * 256 * 256;
                    while (divisor >= 1) {
                        if (index < bytes.length) {
                            bytes[index++] = (byte)((value / divisor) % 256);
                        }
                        divisor /= 256;
                    }
                    value = 0;
                }
            }
            return bytes;
        }

        public ByteBuffer decodeByteBuffer(String string) {
            return ByteBuffer.wrap(decodeBytes(string));
        }
    }
}
