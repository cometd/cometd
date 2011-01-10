package org.cometd.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

import junit.framework.Assert;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.eclipse.jetty.util.log.Log;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatchman;
import org.junit.runners.model.FrameworkMethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ServerMessageImplTest
{
    @Rule
    public final TestWatchman testName = new TestWatchman()
    {
        @Override
        public void starting(FrameworkMethod method)
        {
            super.starting(method);
            Log.info("Running {}.{}", method.getMethod().getDeclaringClass().getName(), method.getName());
        }
    };

    @Test
    public void testSimpleContent() throws Exception
    {
        ServerMessageImpl message = new ServerMessageImpl();
        message.put("channel", "/foo/bar");

        assertEquals(1, message.size());
        assertEquals("/foo/bar", message.getChannel());
        Assert.assertEquals("channel", message.keySet().iterator().next());
        Assert.assertEquals("/foo/bar", message.values().iterator().next());
    }

    @Test
    public void testFrozenBehavior() throws Exception
    {
        String originalJSON = "{" +
                "\"id\":\"12345\"," +
                "\"clientId\":\"jva73siaj92jdafa\"," +
                "\"data\":{" +
                "   \"dataName\":\"dataValue\"," +
                "   \"innerData\":{" +
                "   }" +
                "}," +
                "\"ext\":{" +
                "   \"extName\":\"extValue\"" +
                "}" +
                "}";

        ServerMessage.Mutable[] messages = ServerMessageImpl.parseServerMessages(originalJSON);
        ServerMessageImpl message = (ServerMessageImpl)messages[0];

        String json = message.getJSON();
        assertTrue(json.contains("\"ext\":{\"extName\":\"extValue\"}"));
        assertTrue(json.contains("\"clientId\":\"jva73siaj92jdafa\""));
        assertTrue(json.contains("\"dataName\":\"dataValue\""));
        assertTrue(json.contains("\"id\":\"12345\""));
        assertEquals("12345", message.getId());

        // Modify the message
        message.put("id", "54321");
        Assert.assertEquals("54321", message.getId());

        // Be sure the JSON reflects the modifications
        json = message.getJSON();
        assertTrue(json.contains("\"id\":\"54321\""));

        // Freeze the message
        message.freeze();

        try
        {
            message.put("id", "666");
            fail();
        }
        catch (UnsupportedOperationException expected)
        {
        }

        Assert.assertEquals("54321", message.getId());
        Assert.assertEquals("54321", message.get(Message.ID_FIELD));

        try
        {
            message.getDataAsMap().put("x", "9");
            fail();
        }
        catch (UnsupportedOperationException expected)
        {
        }

        try
        {
            ((Map<String, Object>)message.getData()).put("x", "9");
            fail();
        }
        catch (UnsupportedOperationException expected)
        {
        }

        try
        {
            message.getExt().put("x", "9");
            fail();
        }
        catch (UnsupportedOperationException expected)
        {
        }

        // Deep modifications after the message is frozen are discarded
        Map<String, Object> innerData = (Map<String, Object>)message.getDataAsMap().get("innerData");
        innerData.put("newContent", true);

        String json2 = message.getJSON();
        assertEquals(json, json2);
    }

    @Test
    public void testSerialization() throws Exception
    {
        ServerMessageImpl message = new ServerMessageImpl();
        message.setChannel("/channel");
        message.setClientId("clientId");
        message.setId("id");
        message.setSuccessful(true);
        message.getDataAsMap(true).put("data1", "dataValue1");
        message.getExt(true).put("ext1", "extValue1");
        message.setLazy(true);
        ServerMessageImpl associated = new ServerMessageImpl();
        associated.put("associated", true);
        message.setAssociated(associated);
        message.freeze();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(message);
        oos.close();

        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()));
        ServerMessageImpl deserialized = (ServerMessageImpl)ois.readObject();

        assertEquals(message, deserialized);
        assertTrue(deserialized.isLazy());
        assertNull(deserialized.getAssociated());

        // Make sure the message is still frozen
        try
        {
            deserialized.put("a", "b");
            fail();
        }
        catch (UnsupportedOperationException expected)
        {
        }
    }

    @Test
    public void testModificationViaEntrySet() throws Exception
    {
        ServerMessageImpl message = new ServerMessageImpl();
        message.setChannel("/channel");

        for (Map.Entry<String, Object> field : message.entrySet())
        {
            if (Message.CHANNEL_FIELD.equals(field.getKey()))
            {
                field.setValue("/foo");
                break;
            }
        }

        message.freeze();

        for (Map.Entry<String, Object> field : message.entrySet())
        {
            if (Message.CHANNEL_FIELD.equals(field.getKey()))
            {
                try
                {
                    field.setValue("/foo");
                    fail();
                }
                catch (UnsupportedOperationException expected)
                {
                    break;
                }
            }
        }
    }

    @Test
    public void testNullValue() throws Exception
    {
        String originalJSON = "{" +
                "\"id\":\"12345\"," +
                "\"clientId\":\"jva73siaj92jdafa\"," +
                "\"data\":{" +
                "   \"bar\":5," +
                "   \"nullData\":null" +
                "  }" +
                "}";

        ServerMessage.Mutable[] messages = ServerMessageImpl.parseServerMessages(originalJSON);
        ServerMessageImpl message = (ServerMessageImpl)messages[0];
        Map<String, Object> data = message.getDataAsMap();
        assertNull(data.get("nullData"));
        assertTrue(data.containsKey("nullData"));
        assertEquals(2, data.size());
    }
}
