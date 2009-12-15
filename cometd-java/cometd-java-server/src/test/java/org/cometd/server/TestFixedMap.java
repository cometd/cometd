package org.cometd.server;

import java.util.Iterator;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;


public class TestFixedMap 
{

    @Test
    public void testMap()
    {
        FixedMap<String,Object> map = new FixedMap<String,Object>(2);
        
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
        FixedMap<String,Object> map = new FixedMap<String,Object>(2);
        
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
}
