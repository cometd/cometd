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
package org.cometd.common;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class Z85Test {
    @Test
    public void testZ85Basic() {
        byte[] bytes = new byte[]{-122, 79, -46, 111, -75, 89, -9, 91};
        String string = Z85.encoder.encodeBytes(bytes);
        Assertions.assertEquals("HelloWorld", string);
        byte[] result = Z85.decoder.decodeBytes(string);
        Assertions.assertArrayEquals(bytes, result);
    }

    @Test
    public void testZ85Unpadded() {
        byte[] bytes = new byte[]{0xC, 0x0, 0xF, 0xF, 0xE, 0xE};
        byte[] result = Z85.decoder.decodeBytes(Z85.encoder.encodeBytes(bytes));
        Assertions.assertArrayEquals(bytes, result);
    }
}
