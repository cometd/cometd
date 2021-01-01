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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HashMapMessageTest {
    @Test
    public void testSerialization() throws Exception {
        HashMapMessage message = new HashMapMessage();
        message.setChannel("/channel");
        message.setClientId("clientId");
        message.setId("id");
        message.setSuccessful(true);
        message.getDataAsMap(true).put("data1", "dataValue1");
        message.getExt(true).put("ext1", "extValue1");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(message);
        oos.close();

        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()));
        HashMapMessage deserialized = (HashMapMessage)ois.readObject();

        Assertions.assertEquals(message, deserialized);
    }
}
