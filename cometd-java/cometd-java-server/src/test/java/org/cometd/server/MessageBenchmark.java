package org.cometd.server;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.cometd.bayeux.Message;
import org.eclipse.jetty.util.BlockingArrayQueue;

public class MessageBenchmark
{   
    public static void main(String[] arg) throws Exception
    {
        System.err.print("warmup :");
        long hash=hashMapMessageTest(10,2000);
        long imut=immutableMessageTest(10,2000);
        System.err.println();
        
        System.err.print("20x2000:");
        Runtime.getRuntime().gc();
        hash=hashMapMessageTest(20,2000);
        Runtime.getRuntime().gc();
        imut=immutableMessageTest(20,2000);
        System.err.println("\thash="+hash+"\timutable="+imut+"\tgain="+((hash-imut)*100/hash)+"%");
        
        System.err.print("30x3000:");
        Runtime.getRuntime().gc();
        hash=hashMapMessageTest(30,3000);
        Runtime.getRuntime().gc();
        imut=immutableMessageTest(30,3000);
        System.err.println("\thash="+hash+"\timutable="+imut+"\tgain="+((hash-imut)*100/hash)+"%");
        
        System.err.print("40x4000:");
        Runtime.getRuntime().gc();
        hash=hashMapMessageTest(40,4000);
        Runtime.getRuntime().gc();
        imut=immutableMessageTest(40,4000);
        System.err.println("\thash="+hash+"\timutable="+imut+"\tgain="+((hash-imut)*100/hash)+"%");
        
        System.err.print("50x5000:");
        Runtime.getRuntime().gc();
        hash=hashMapMessageTest(50,5000);
        Runtime.getRuntime().gc();
        imut=immutableMessageTest(50,5000);
        System.err.println("\thash="+hash+"\timutable="+imut+"\tgain="+((hash-imut)*100/hash)+"%");
        
    }
     
    static long immutableMessageTest(final int threads,final int loops) throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(2*threads);
        final AtomicLong bigResult=new AtomicLong();
        
        final BlockingArrayQueue<Message.Mutable>[] q = new BlockingArrayQueue[threads];
        for (int i=0;i<threads;i++)
            q[i]=new BlockingArrayQueue<Message.Mutable>(threads*10,threads); 
        long start=System.currentTimeMillis();
        for (int i=0;i<threads;i++)
        {
            final int index=i;
            new Thread()
            {
                public void run()
                {  
                    long result=0;
                    
                    for (int m=0;m<loops*threads;m++)
                    {
                        try
                        {
                            Message msg = q[index].poll(10,TimeUnit.SECONDS);
                            // System.err.println("m="+msg);
                            result += msg.getId().hashCode();
                            result += msg.getChannelId().hashCode();
                            Map<String, Object> data=(Map<String, Object>)msg.getData();
                            
                            result += data.get("name").hashCode();
                            result += data.get("chat").hashCode();
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                    }

                    bigResult.addAndGet(result);
                    latch.countDown();
                }
            }.start();
        }
        
        for (int i=0;i<threads;i++)
        {
            final int index=i;
            new Thread()
            {
                public void run()
                {
                    long result=0;
                    
                    for (int m=0;m<loops;m++)
                    {   
                        Message.Mutable msg = new HashMapMessage();
                        
                        // pretend to parse the message.
                        msg.put(Message.ID_FIELD,"12345");
                        msg.put(Message.CHANNEL_FIELD,"/foo/bar/wibble");
                        Map<String, Object> data=msg.getMutableData();
                        data.put("name","gregw");
                        data.put("chat","Now is the time for all good men to come to the aid of the party");
                        msg.put("timestamp",new Long(System.currentTimeMillis()));
                        
                        // pretend to use the message
                        result += msg.getId().hashCode();
                        result += msg.getChannelId().hashCode();

                        
                        for (int i=0;i<threads;i++)
                        {
                            q[i].offer(msg);
                        }
                        Thread.yield();
                    }
                    
                    bigResult.addAndGet(result);
                    latch.countDown();
                }
            }.start();
        }
        latch.await();
        System.err.print("\t"+bigResult);
        return System.currentTimeMillis()-start;
        
    }

    static long hashMapMessageTest(final int threads,final int loops) throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(2*threads);
        final AtomicLong bigResult=new AtomicLong();
        final ImmutableMessagePool pool = new ImmutableMessagePool();

        final BlockingArrayQueue<ImmutableMessage>[] q = new BlockingArrayQueue[threads];
        for (int i=0;i<threads;i++)
            q[i]=new BlockingArrayQueue<ImmutableMessage>(threads*10,threads); 
        long start=System.currentTimeMillis();
        for (int i=0;i<threads;i++)
        {
            final int index=i;
            new Thread()
            {
                public void run()
                {  

                    long result=0;

                    for (int m=0;m<loops*threads;m++)
                    {
                        try
                        {
                            ImmutableMessage msg = q[index].poll(10,TimeUnit.SECONDS);
                            // System.err.println("m="+msg);
                            result += msg.getId().hashCode();
                            result += msg.getChannelId().hashCode();
                            Map<String, Object> data=(Map<String, Object>)msg.getData();

                            result += data.get("name").hashCode();
                            result += data.get("chat").hashCode();

                            msg.decRef();
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                    }

                    bigResult.addAndGet(result);
                    latch.countDown();
                }
            }.start();
        }

        for (int i=0;i<threads;i++)
        {
            final int index=i;
            new Thread()
            {
                public void run()
                {
                    long result=0;

                    for (int m=0;m<loops;m++)
                    {   
                        Message.Mutable msg = pool.newMessage();

                        // pretend to parse the message.
                        msg.put(Message.ID_FIELD,"12345");
                        msg.put(Message.CHANNEL_FIELD,"/foo/bar/wibble");
                        Map<String, Object> data=msg.getMutableData();
                        data.put("name","gregw");
                        data.put("chat","Now is the time for all good men to come to the aid of the party");
                        msg.put("timestamp",new Long(System.currentTimeMillis()));

                        // pretend to use the message
                        result += msg.getId().hashCode();
                        result += msg.getChannelId().hashCode();

                        ImmutableMessage immutable = ((ImmutableMessage.MutableMessage)msg).asImmutable();

                        for (int i=0;i<threads;i++)
                        {
                            immutable.incRef();
                            q[i].offer(immutable);
                        }
                        immutable.decRef();
                        Thread.yield();
                    }

                    bigResult.addAndGet(result);
                    latch.countDown();
                }
            }.start();
        }
        latch.await();
        System.err.print("\t"+bigResult);
        return System.currentTimeMillis()-start;

    }

}
