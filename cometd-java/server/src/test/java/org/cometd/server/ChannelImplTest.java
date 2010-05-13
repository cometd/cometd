/**
 *
 */

package org.cometd.server;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.TestCase;
import org.cometd.Channel;
import org.cometd.ChannelBayeuxListener;
import org.cometd.ConfigurableChannel;

/**
 * @version $Revision: 1035 $ $Date: 2010-03-22 11:59:52 +0100 (Mon, 22 Mar 2010) $
 */
public class ChannelImplTest extends TestCase
{
    private AbstractBayeux _bayeux;

    public void setUp() throws Exception
    {
        _bayeux = new BayeuxStub();
    }

    public void testConcurrentGetChannel() throws Exception
    {
        // Tests the race condition where a channel is created concurrently by 2 threads.
        //
        // There are 2 steps in the channel creation: the first is to populate internal
        // data structures so that the channel can be retrieved by subsequent calls;
        // the second is to notify listeners that are interested in configuring the
        // channel (for example adding a listener).
        // These 2 steps must be atomic, otherwise a race is possible where T1 creates
        // the channel, populates the data structures, but is preempted before calling
        // the listeners that configure the channel; and T2 that creates the channel,
        // sees it already in the data structures, then publishes a message to it.
        // In this case, a message listener can be notified before the channel is fully
        // configured.
        //
        // See http://bugs.cometd.org/browse/COMETD-112

        final String channelName = "/test";
        final CountDownLatch latch1 = new CountDownLatch(1);
        final CountDownLatch latch2 = new CountDownLatch(1);
        _bayeux.addListener(new ConfigurableChannel.Initializer()
        {
            public void configureChannel(ConfigurableChannel channel)
            {
                try
                {
                    // Be sure T1 arrives here before T2 creates the channel
                    latch1.countDown();

                    // T1 will wait here while T2 creates the channel
                    assertFalse(latch2.await(1000, TimeUnit.MILLISECONDS));
                }
                catch (InterruptedException x)
                {
                    assertTrue("Interrupted", false);
                }
            }
        });

        // Thread T1 asks for the channel, it will trigger the listener
        final AtomicReference<Channel> channelRef = new AtomicReference<Channel>();
        final CountDownLatch latch3 = new CountDownLatch(1);
        new Thread()
        {
            @Override
            public void run()
            {
                Channel channel = _bayeux.getChannel(channelName, true);
                channelRef.set(channel);
                latch3.countDown();
            }
        }.start();
        assertTrue(latch1.await(1000, TimeUnit.MILLISECONDS));

        // Thread T2 asks for the channel, it will not trigger the listener
        // It must wait for thread T1 to call the listener
        // If it will not wait, latch2 will be counted down and the assert
        // in configureChannel() above will fail, failing the test
        Channel channel = _bayeux.getChannel(channelName, true);
        assertNotNull(channel);
        latch2.countDown();

        assertTrue(latch3.await(1000, TimeUnit.MILLISECONDS));
        assertNotNull(channelRef.get());
        assertSame(channel, channelRef.get());
    }

    public void testReentrantGetChannelFromInitializer()
    {
        long maxInterval = 2000;
        _bayeux.setMaxInterval(maxInterval);
        _bayeux.addListener(new ConfigurableChannel.Initializer()
        {
            public void configureChannel(ConfigurableChannel channel)
            {
                // Reentrant call is forbidden here:
                // we are initializing the channel and cannot obtain it
                try
                {
                    _bayeux.getChannel(channel.getId());
                    fail();
                }
                catch (IllegalStateException x)
                {
                    // Expected
                }
            }
        });
        _bayeux.getChannel("/test", true);
    }

    public void testConcurrentReentrantGetChannelFromChannelAddedListener() throws Exception
    {
        final CountDownLatch latch1 = new CountDownLatch(1);
        final CountDownLatch latch2 = new CountDownLatch(1);
        final CountDownLatch userLatch = new CountDownLatch(1);
        _bayeux.addListener(new ChannelBayeuxListener()
        {
            public void channelAdded(Channel channel)
            {
                try
                {
                    latch1.countDown();
                    // Reentrant call
                    Channel c = _bayeux.getChannel(channel.getId());
                    assertNotNull(c);
                    assertTrue(latch2.await(1000, TimeUnit.MILLISECONDS));
                    userLatch.countDown();
                }
                catch (InterruptedException x)
                {
                    assertTrue("Interrupted", false);
                }
            }

            public void channelRemoved(Channel channel)
            {
            }
        });

        final AtomicReference<Channel> channelRef = new AtomicReference<Channel>();
        final CountDownLatch latch3 = new CountDownLatch(1);
        final String name = "/test";
        new Thread()
        {
            @Override
            public void run()
            {
                channelRef.set(_bayeux.getChannel(name, true));
                latch3.countDown();
            }
        }.start();
        assertTrue(latch1.await(1000, TimeUnit.MILLISECONDS));

        Channel channel = _bayeux.getChannel(name, true);
        assertNotNull(channel);
        latch2.countDown();
        assertTrue(userLatch.await(1000, TimeUnit.MILLISECONDS));

        assertTrue(latch3.await(1000, TimeUnit.MILLISECONDS));
        assertNotNull(channelRef.get());
        assertSame(channel, channelRef.get());
    }

    public void testRemoves() throws Exception
    {
        String[][] tests = new String[][]{
                // added,  expected   , remove , removed, expected
                {"/test", "[/, /test]", "/test", "/test", "[/]"},
                {"/test/123", "[/, /test, /test/123]", "/test/123", "/test/123", "[/]"},
                {"/test/123", "[/, /test, /test/123]", "/test/abc", null, "[/, /test, /test/123]"},
                {"/test/123", "[/, /test, /test/123]", "/123", null, "[/, /test, /test/123]"},
                {"/test/123", "[/, /test, /test/123]", "/test", "/test", "[/]"}
        };

        for (String[] test : tests)
        {
            _bayeux.getChannel(test[0], true);
            assertEquals(test[1], _bayeux.getChannels().toString());

            Channel removed = _bayeux.removeChannel(test[2]);
            assertEquals(test[3], removed == null ? null : removed.toString());
            assertEquals(test[4], _bayeux.getChannels().toString());
        }
    }

    public void testChannelSubscriptions() throws Exception
    {
        String[] channels = {"/service", "/service/foo", "/service/foo/bar", "/service/foo/bob"};
        final String[] results = new String[channels.length];
        _bayeux.addListener(new ChannelBayeuxListener()
        {
            private int r = 0;

            public void channelAdded(Channel channel)
            {
                results[r++] = channel.getId();
            }

            public void channelRemoved(Channel channel)
            {
            }
        });
        _bayeux.getChannel("/service/foo/bar", true);
        _bayeux.getChannel("/service/foo/bob", true);
        assertTrue(Arrays.equals(channels, results));
    }

    public void testChannelCount() throws Exception
    {
        assertEquals(1, _bayeux.getChannelCount());
        _bayeux.getChannel("/root/1", true);
        assertEquals(3, _bayeux.getChannelCount());
        _bayeux.getChannel("/root/2", true);
        assertEquals(4, _bayeux.getChannelCount());
        _bayeux.getChannel("/root/1/2", true);
        _bayeux.getChannel("/root/1/A/B", true);
        assertEquals(7, _bayeux.getChannelCount());
    }

    private static class BayeuxStub extends AbstractBayeux
    {
        public BayeuxStub()
        {
            try
            {
                _random = SecureRandom.getInstance("SHA1PRNG");
            }
            catch (Exception e)
            {
                _random = new Random();
            }
            _random.setSeed(_random.nextLong() ^ hashCode() ^ Runtime.getRuntime().freeMemory());
        }

        public ClientImpl newRemoteClient()
        {
            return null;
        }
    }
}
