package org.cometd.util;

import java.util.Iterator;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;


public class TestImmutableHashMap 
{
    @Test
    public void testMap()
    {
        ImmutableHashMap<String,Object>.Mutable mutable = new ImmutableHashMap<String,Object>(2).asMutable();
        
        Assert.assertTrue(mutable.isEmpty());
        Assert.assertEquals(0,mutable.size());
        
        mutable.put("A","one");
        mutable.put("B","2");
        mutable.put("C","3");
        mutable.put("A","1");
        mutable.put("D","4");
       
        Assert.assertFalse(mutable.isEmpty());
        Assert.assertEquals(4,mutable.size());
        Assert.assertEquals("1",mutable.get("A"));
        Assert.assertEquals("2",mutable.get("B"));
        Assert.assertEquals("3",mutable.get("C"));
        Assert.assertEquals("4",mutable.get("D"));
        Assert.assertTrue(mutable.containsKey("D"));
        Assert.assertFalse(mutable.containsKey("E"));
        Assert.assertEquals("D",mutable.getEntry("D").getKey()); 
        Assert.assertEquals("4",mutable.getEntry("D").getValue());  
        
        
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

        mutable.put("B",null); 
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

}
