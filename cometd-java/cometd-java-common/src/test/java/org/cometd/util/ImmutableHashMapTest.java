package org.cometd.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.cometd.util.ImmutableHashMap.MutableEntry;
import org.junit.Assert;
import org.junit.Test;


public class ImmutableHashMapTest 
{
    @Test
    public void testMap()
    {
        ImmutableHashMap<String,Object>.Mutable mutable = new ImmutableHashMap<String,Object>(2).asMutable();
        
        Assert.assertTrue(mutable.isEmpty());
        Assert.assertEquals(0,mutable.size());
        
        mutable.put("A","one");
        MutableEntry<String, Object> bee = mutable.getEntryReference("B");
        bee.setValue("2");
        mutable.put("C","3");
        mutable.put("A","1");
        mutable.put("D","4");
       
        Assert.assertFalse(mutable.isEmpty());
        Assert.assertEquals(4,mutable.size());
        Assert.assertEquals("1",mutable.get("A"));
        Assert.assertEquals("2",mutable.get("B"));
        Assert.assertEquals("2",bee.getValue());
        Assert.assertEquals("2",bee.asImmutable().getValue());
        Assert.assertEquals("3",mutable.get("C"));
        Assert.assertEquals("4",mutable.get("D"));
        Assert.assertTrue(mutable.containsKey("D"));
        Assert.assertFalse(mutable.containsKey("E"));
        Assert.assertEquals("D",mutable.getEntry("D").getKey()); 
        Assert.assertEquals("4",mutable.getEntry("D").getValue());  
        
        Set<String> temp = new HashSet<String>();
        for (String key : mutable.keySet())
            temp.add(key);
        Assert.assertTrue(temp.contains("A"));
        Assert.assertTrue(temp.contains("B"));
        Assert.assertTrue(temp.contains("C"));
        Assert.assertTrue(temp.contains("D"));
        
        temp.clear();
        for (String key : mutable.asImmutable().keySet())
            temp.add(key);
        Assert.assertTrue(temp.contains("A"));
        Assert.assertTrue(temp.contains("B"));
        Assert.assertTrue(temp.contains("C"));
        Assert.assertTrue(temp.contains("D"));
        
        temp.clear();
        for (Object value : mutable.values())
            temp.add(String.valueOf(value));
        Assert.assertTrue(temp.contains("1"));
        Assert.assertTrue(temp.contains("2"));
        Assert.assertTrue(temp.contains("3"));
        Assert.assertTrue(temp.contains("4"));
        
        temp.clear();
        for (Object value : mutable.asImmutable().values())
            temp.add(String.valueOf(value));
        Assert.assertTrue(temp.contains("1"));
        Assert.assertTrue(temp.contains("2"));
        Assert.assertTrue(temp.contains("3"));
        Assert.assertTrue(temp.contains("4"));
        
        boolean[] keys={false,false,false,false};
        for (String s: mutable.keySet())
            keys[s.charAt(0)-'A']=true;
        for (boolean b:keys)
            Assert.assertTrue(b);
        
        mutable.remove("A"); 
        Assert.assertEquals(3,mutable.size());
        Assert.assertEquals(null,mutable.get("A"));
        Assert.assertEquals("2",mutable.get("B"));
        Assert.assertEquals("3",mutable.get("C"));
        Assert.assertEquals("4",mutable.get("D"));

        bee.setValue(null);
        Assert.assertEquals(2,mutable.size());
        Assert.assertEquals(null,mutable.get("A"));
        Assert.assertEquals(null,mutable.get("B"));
        Assert.assertEquals("3",mutable.get("C"));
        Assert.assertEquals("4",mutable.get("D"));

        Iterator<Map.Entry<String,Object>> iter = mutable.entrySet().iterator();
        while (iter.hasNext())
        {
            Map.Entry<String,Object> e=iter.next();
            if (e.getKey().equals("D"))
                iter.remove();
        }
            
        Assert.assertEquals(1,mutable.size());
        Assert.assertEquals(null,mutable.get("A"));
        Assert.assertEquals(null,mutable.get("B"));
        Assert.assertEquals("3",mutable.get("C"));
        Assert.assertEquals(null,mutable.get("D"));
        
        mutable.keySet().clear();
        Assert.assertTrue(mutable.isEmpty());
        Assert.assertEquals(0,mutable.size());

        mutable.put("A","1");
        mutable.put("B","2");
        mutable.put("C","3");
        mutable.put("D","4");
        mutable.put("E","5");

        Assert.assertFalse(mutable.isEmpty());
        Assert.assertEquals(5,mutable.size());
        mutable.clear();
        Assert.assertTrue(mutable.isEmpty());
        Assert.assertEquals(0,mutable.size());

        mutable.getEntry("D").setValue("four");
        Assert.assertEquals("four",mutable.getEntry("D").getValue());  

        try
        {
            mutable.asImmutable().getEntry("D").setValue("IV");
            Assert.assertTrue(false);
        }
        catch(UnsupportedOperationException e)
        {
            Assert.assertTrue(true);
        }
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

    @Test
    public void testMapOfMaps()
    {
        ImmutableHashMap<String,Object> map = new ImmutableHashMap<String,Object>();
        Map<String,Object> data = new HashMap<String,Object>();
        try {  map.put("data", data); Assert.assertTrue(false); } catch (UnsupportedOperationException e) { Assert.assertTrue(true);}
        map.asMutable().put("data", data);
        data.put("field", "value");
        Assert.assertEquals("{data={field=value}}", map.toString());
        
        Entry<String, Object> dataRef=map.asMutable().getEntry("data");
        ((Map<String,Object>)dataRef.getValue()).put("field","other");
        Assert.assertEquals("{data={field=other}}", map.toString());
        
        dataRef=map.getEntryReference("data");
        try {  ((Map<String,Object>)dataRef.getValue()).put("field","nope"); Assert.assertTrue(false); } catch (UnsupportedOperationException e) { Assert.assertTrue(true);}
        Assert.assertEquals("{data={field=other}}", map.toString());      
    }
}
