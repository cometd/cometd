package org.cometd.server;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import junit.framework.Assert;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.util.ImmutableHashMap;
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

    @Test
    public void testData() throws Exception
    {
    	ServerMessageImpl message = new ServerMessageImpl();
    	ImmutableHashMap<String, Object> data = new ImmutableHashMap<String, Object>();
    	data.asMutable().put("field", "value");
    	message.asMutable().put("data", data.asMutable());
    	
    	Assert.assertEquals("|{\"data\":{\"field\":\"value\"}}|", message.toString());

    	Map<String,Object> d2=((Map<String,Object>)message.asMutable().getData());
        Iterator<Entry<String,Object>> iter=d2.entrySet().iterator();
        while(iter.hasNext())
        {
            Map.Entry entry=(Map.Entry)iter.next();
            entry.setValue("other");
        }
        
        Assert.assertTrue(data.asMutable()==d2);

    	Assert.assertEquals("{field=other}", d2.toString());
    	Assert.assertEquals("{field=other}", message.getData().toString());
    	Assert.assertEquals("|{\"data\":{\"field\":\"other\"}}|", message.toString());
    	
    }
}
