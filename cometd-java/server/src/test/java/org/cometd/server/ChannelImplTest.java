/**
 *
 */
package org.cometd.server;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

import junit.framework.TestCase;
import org.cometd.Channel;
import org.cometd.ChannelBayeuxListener;

/**
 * @author athena
 *
 */
public class ChannelImplTest extends TestCase
{
    private Channel _channel;
    private AbstractBayeux _bayeux;
    public void setUp() throws Exception
    {
        _bayeux = new BayeuxStub();
    }

    public void testRemoves() throws Exception
    {
        String[][] tests = new String[][] {
           // added,  expected   , remove , removed, expected
           {"/test", "[/, /test]", "/test", "/test", "[/]"},
           {"/test/123", "[/, /test, /test/123]", "/test/123", "/test/123", "[/]"},
           {"/test/123", "[/, /test, /test/123]", "/test/abc", null, "[/, /test, /test/123]"},
           {"/test/123", "[/, /test, /test/123]", "/123", null, "[/, /test, /test/123]"},
           {"/test/123", "[/, /test, /test/123]", "/test", "/test", "[/]"}
        };

        for ( String[] test : tests )
        {
            _bayeux.getChannel(test[0], true);
            assertEquals(test[1], _bayeux.getChannels().toString());

            Channel removed = _bayeux.removeChannel(test[2]);
            assertEquals(test[3], removed == null? null : removed.toString());
            assertEquals(test[4], _bayeux.getChannels().toString());
        }
    }

    public void testChannelSubscriptions () throws Exception
    {
      String[] chans = {"/service", "/service/foo", "/service/foo/bar", "/service/foo/bob"};
      final String[] results = new String[chans.length];


      class MyChannelBayeuxListener implements ChannelBayeuxListener
      {
          int r = 0;
          String[] res;
          public MyChannelBayeuxListener (String[] res)
          {
              this.res=res;
          }
          public void channelAdded(Channel channel)
          {
              this.res[r++] = channel.getId();
          }

          public void channelRemoved(Channel channel)
          {

          }
      }

      _bayeux.addListener (new MyChannelBayeuxListener(results));
      _bayeux.getChannel("/service/foo/bar", true);
      _bayeux.getChannel("/service/foo/bob", true);
      assertTrue(Arrays.equals(chans,results));
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

    static class BayeuxStub extends AbstractBayeux
    {
        public BayeuxStub()
        {
            try
            {
                _random=SecureRandom.getInstance("SHA1PRNG");
            }
            catch (Exception e)
            {
                _random=new Random();
            }

            _random.setSeed(_random.nextLong()^hashCode()^Runtime.getRuntime().freeMemory());
        }

        public ClientImpl newRemoteClient()
        {
            return null;
        }
    }
}
