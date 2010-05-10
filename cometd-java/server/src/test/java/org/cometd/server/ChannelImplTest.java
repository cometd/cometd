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

    public void testAddNotificationIsAtomicWithCreation() throws Exception
    {
        // Tests the race condition where a channel is created concurrently by 2 threads.
        // The first thread will add the channel to a map<name, channel> and then notify
        // listeners that the channel has been added.
        // The second thread will see the channel in the map, and could return before
        // the add listeners are notified by the first thread, which could break applications
        // that expect the create-notify to be atomic.
        // See http://bugs.cometd.org/browse/COMETD-112
        final CountDownLatch latch1 = new CountDownLatch(1);
        final CountDownLatch latch2 = new CountDownLatch(1);
        _bayeux.addListener(new ChannelBayeuxListener()
        {
            public void channelAdded(Channel channel)
            {
                try
                {
                    latch1.countDown();
                    assertFalse(latch2.await(1000, TimeUnit.MILLISECONDS));
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

        // Thread 1 asks for the channel, it triggers the add listeners
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

        // Thread 2 asks for the channel, it does not trigger the listeners
        // It must wait for thread 1 to call the add listeners
        Channel channel = _bayeux.getChannel(name, true);
        assertNotNull(channel);
        latch2.countDown();

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
