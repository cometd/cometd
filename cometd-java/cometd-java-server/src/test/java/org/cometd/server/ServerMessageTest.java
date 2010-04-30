package org.cometd.server;

import java.util.Map;

import junit.framework.Assert;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.junit.Test;


public class ServerMessageTest
{
    @Test
    public void testGeneral() throws Exception
    {
        String json = "{\"id\":\"12345\", \"clientId\":\"jva73siaj92jdafa\", \"data\":{\"name\":\"value\"}, \"ext\":{\"name\":\"value\"}}";

        Message[] messages=ServerMessageImpl.parseMessages(json);
        ServerMessageImpl.MutableMessage mutable= (ServerMessageImpl.MutableMessage)messages[0];
        ServerMessageImpl immutable= mutable.asImmutable();

        String s=mutable.toString();
        Assert.assertTrue(s.contains("\"ext\":{\"name\":\"value\"}"));
        Assert.assertTrue(s.contains("\"clientId\":\"jva73siaj92jdafa\""));
        Assert.assertTrue(s.contains("\"data\":{\"name\":\"value\"}"));
        Assert.assertTrue(s.contains("\"id\":\"12345\""));

        Assert.assertEquals("12345",mutable.getId());
        Assert.assertEquals("12345",immutable.getId());
        try{immutable.put("id","666"); Assert.assertTrue(false);}catch(UnsupportedOperationException e){Assert.assertTrue(true);};
        Assert.assertEquals("12345",mutable.getId());
        Assert.assertEquals("12345",immutable.getId());
        mutable.put("id","54321");
        Assert.assertEquals("54321",mutable.getId());
        Assert.assertEquals("54321",immutable.getId());

        try{((Map<String, Object>)immutable.get("data")).put("x","9"); Assert.assertTrue(false);}catch(UnsupportedOperationException e){Assert.assertTrue(true);};
        try{((Map<String, Object>)immutable.getData()).put("x","9"); Assert.assertTrue(false);}catch(UnsupportedOperationException e){Assert.assertTrue(true);};

        ((Map<String, Object>)mutable.get("data")).put("x","8");
        ((Map<String, Object>)mutable.getData()).put("y","9");
        Assert.assertEquals("8",immutable.getDataAsMap().get("x"));
        Assert.assertEquals("9",immutable.getDataAsMap().get("y"));
    }
    
    @Test
    public void testSpecific() throws Exception
    {
        ServerMessageImpl.Mutable mutable= new ServerMessageImpl().asMutable();
        ServerMessage immutable = mutable.asImmutable();
        
        mutable.put("channel","/foo/bar");
        
        Assert.assertEquals(1,mutable.size());
        
        Assert.assertEquals("/foo/bar",mutable.getChannel());
        Assert.assertEquals("/foo/bar",immutable.getChannel());
        Assert.assertEquals("channel",immutable.keySet().iterator().next());
        Assert.assertEquals("/foo/bar",immutable.values().iterator().next());

        
    }
}
