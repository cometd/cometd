package org.cometd.server;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import junit.framework.AssertionFailedError;

import org.eclipse.jetty.util.ArrayQueue;
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

    final static int THREADS=100;
    final static int LOOPS=50000;
    
    public static void main(String[] arg) throws Exception
    {
        for (int t=0;t<10;t++)
        {
            Runtime.getRuntime().gc();
            System.err.println("map  "+mapTest());
            Runtime.getRuntime().gc();
            System.err.println("pool "+immutableMapTest());
        }
    }
     
    static long immutableMapTest() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(THREADS);
        final AtomicLong bigResult=new AtomicLong();
        final ArrayQueue<Message> queue=new ArrayQueue<Message>(100,50);
        
        long start=System.currentTimeMillis();
        for (int i=0;i<THREADS;i++)
        {
            new Thread()
            {
                public void run()
                {
                    queue.add(new Message());
                    long result=0;
                    
                    for (int m=0;m<LOOPS;m++)
                    {
                        final Message msg = queue.remove();
                        //Message msg = new Message();
                        
                        // pretend to parse the message.
                        msg._mutable.put("id","12345");
                        msg._mutable.put("channelid","/foo/bar/wibble");
                        ImmutableHashMap<String, Object> data=new ImmutableHashMap<String, Object>();
                        data.asMutable().put("name","gregw");
                        data.asMutable().put("chat","Now is the time for all good men to come to the aid of the party");
                        msg._mutable.put("data",data);
                        msg._mutable.put("timestamp","1970 Jan 1");
                        
                        // pretend to use the message
                        result += msg._id.getValue().hashCode();
                        result += msg._channel.getValue().hashCode();
                        result += msg._id.getValue().hashCode();
                        result += msg._channel.getValue().hashCode();

                        data=(ImmutableHashMap<String, Object>)msg._data.getValue();
                        
                        result += data.get("name").hashCode();
                        result += data.get("chat").hashCode();
                        
                        queue.add(msg);
                        Thread.yield();
                    }
                    
                    bigResult.addAndGet(result);
                    latch.countDown();
                    
                }
            }.start();
        }
        latch.await();
        return System.currentTimeMillis()-start;
        
    }
    static long mapTest() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(THREADS);
        final AtomicLong bigResult=new AtomicLong();
        
        long start=System.currentTimeMillis();
        for (int i=0;i<THREADS;i++)
        {
            new Thread()
            {
                public void run()
                {
                    long result=0;
                    
                    for (int m=0;m<LOOPS;m++)
                    {
                        Map<String,Object> map = new HashMap<String, Object>();
                        
                        // pretend to parse the message.
                        map.put("id","12345");
                        map.put("channelid","/foo/bar/wibble");
                        HashMap<String, Object> data=new HashMap<String, Object>();
                        data.put("name","gregw");
                        data.put("chat","Now is the time for all good men to come to the aid of the party");
                        map.put("data",data);
                        map.put("timestamp","1970 Jan 1");
                        
                        // pretend to use the message
                        result += map.get("id").hashCode();
                        result += map.get("channelid").hashCode();
                        result += map.get("id").hashCode();
                        result += map.get("channelid").hashCode();

                        data=(HashMap<String, Object>)map.get("data");
                        
                        result += data.get("name").hashCode();
                        result += data.get("chat").hashCode();
                        Thread.yield();
                    }
                    
                    bigResult.addAndGet(result);
                    latch.countDown();
                    
                }
            }.start();
        }
        latch.await();
        return System.currentTimeMillis()-start;
        
    }
    
    static class Message extends ImmutableHashMap<String,Object>
    {
        final Map<String,Object> _mutable;
        final AtomicInteger _refs = new AtomicInteger();
        final Map.Entry<String, Object> _id;
        final Map.Entry<String, Object> _channel;
        final Map.Entry<String, Object> _data;
        Message()
        {
            _mutable=asMutable();
            _mutable.put("id",null);
            _mutable.put("channelid",null);
            _mutable.put("data",null);
            
            _id=getEntry("id");
            _channel=getEntry("channelid");
            _data=getEntry("data");
        }
    }
    
}
