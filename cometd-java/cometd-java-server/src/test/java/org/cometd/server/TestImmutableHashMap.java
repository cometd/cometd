package org.cometd.server;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import junit.framework.AssertionFailedError;

import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.Assert;
import org.junit.Test;


public class TestImmutableHashMap 
{

    @Test
    public void testMap()
    {
        Map<String,Object> map = new ImmutableHashMap<String,Object>(2).asMutable();
        
        Assert.assertTrue(map.isEmpty());
        Assert.assertEquals(0,map.size());
        
        map.put("A","one");
        map.put("B","2");
        map.put("C","3");
        map.put("A","1");
        map.put("D","4");
       
        Assert.assertFalse(map.isEmpty());
        Assert.assertEquals(4,map.size());
        Assert.assertEquals("1",map.get("A"));
        Assert.assertEquals("2",map.get("B"));
        Assert.assertEquals("3",map.get("C"));
        Assert.assertEquals("4",map.get("D"));
        
        
        boolean[] keys={false,false,false,false};
        for (String s: map.keySet())
            keys[s.charAt(0)-'A']=true;
        for (boolean b:keys)
            Assert.assertTrue(b);
        
        map.remove("A"); 
        Assert.assertEquals(3,map.size());
        Assert.assertEquals(null,map.get("A"));
        Assert.assertEquals("2",map.get("B"));
        Assert.assertEquals("3",map.get("C"));
        Assert.assertEquals("4",map.get("D"));

        map.put("B",null); 
        Assert.assertEquals(2,map.size());
        Assert.assertEquals(null,map.get("A"));
        Assert.assertEquals(null,map.get("B"));
        Assert.assertEquals("3",map.get("C"));
        Assert.assertEquals("4",map.get("D"));

        Iterator<Map.Entry<String,Object>> iter = map.entrySet().iterator();
        while (iter.hasNext())
        {
            Map.Entry<String,Object> e=iter.next();
            if (e.getKey().equals("D"))
                iter.remove();
        }
            
        Assert.assertEquals(1,map.size());
        Assert.assertEquals(null,map.get("A"));
        Assert.assertEquals(null,map.get("B"));
        Assert.assertEquals("3",map.get("C"));
        Assert.assertEquals(null,map.get("D"));
        
        map.keySet().clear();
        Assert.assertTrue(map.isEmpty());
        Assert.assertEquals(0,map.size());

        map.put("A","1");
        map.put("B","2");
        map.put("C","3");
        map.put("D","4");
        map.put("E","5");

        Assert.assertFalse(map.isEmpty());
        Assert.assertEquals(5,map.size());
        map.clear();
        Assert.assertTrue(map.isEmpty());
        Assert.assertEquals(0,map.size());
        
    }
    
    @Test
    public void testString()
    {
        Map<String,Object> map = new ImmutableHashMap<String,Object>(2).asMutable();
        
        map.put("A","1");
        map.put("B","2");
        map.put("C","3");
        map.put("D","4");
        map.put("E","5");
        
        String s=map.toString();
        Assert.assertTrue(s.contains("A=1"));
        Assert.assertTrue(s.contains("B=2"));
        Assert.assertTrue(s.contains("C=3"));
        Assert.assertTrue(s.contains("D=4"));
        Assert.assertTrue(s.contains("E=5"));
    }

    @Test
    public void testImmutable()
    {
        ImmutableHashMap<String,Object>.Mutable map = new ImmutableHashMap<String,Object>(2).asMutable();
        
        map.put("A","1");
        map.put("B","2");
        map.put("C","3");
        map.put("D","4");
        map.put("E","5");
        
        ImmutableHashMap<String,Object>.Mutable map2 = new ImmutableHashMap<String,Object>(2).asMutable();
        map2.put("X","1");
        map2.put("Y","2");
        map.put("2",map2.asImmutable());
        
        Map<String,Object> immutable = map.asImmutable();
        
        try { immutable.put("F","6"); Assert.assertTrue(false); } catch (UnsupportedOperationException e) { Assert.assertTrue(true);}
        try { immutable.clear(); Assert.assertTrue(false); } catch (UnsupportedOperationException e) { Assert.assertTrue(true);}
        try { immutable.remove("A"); Assert.assertTrue(false); } catch (UnsupportedOperationException e) { Assert.assertTrue(true);}
        try { immutable.entrySet().clear(); Assert.assertTrue(false); } catch (UnsupportedOperationException e) { Assert.assertTrue(true);}
        try { Iterator i= immutable.entrySet().iterator(); i.next(); i.remove(); Assert.assertTrue(false); } catch (UnsupportedOperationException e) { Assert.assertTrue(true);}
    

        try { ((Map<String,Object>)immutable.get("2")).put("Z","3"); Assert.assertTrue(false); } catch (UnsupportedOperationException e) { Assert.assertTrue(true);}
    
    }

}
