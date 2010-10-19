package org.cometd.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HashMapMessageTest
{
    @Test
    public void testSerialization() throws Exception
    {
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

        assertEquals(message, deserialized);
    }
}
