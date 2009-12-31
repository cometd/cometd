package org.cometd.bwtp.server;

import org.cometd.bwtp.BWTPListener;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class BWTPProcessorTest
{
    @Test
    public void testBWTPProcessor()
    {
        BWTPListener listener = null;

        // 1. get the OPEN header frame and be able to customize the OPENED header frame in response
        // 2. get the MESSAGE header frame and be able to store it locally
        //    onHeaderFrame(BWTPChannel, BWTPMessageFrame)
        // 3. get the message frame
        //    onMessageFrame(BWTPChannel, BWTPMessageFrame)
        // 4. get the CLOSE header frame and be able to customize the CLOSED header frame in response ??? NO
        // 5. be able to close in any moment
        //    channel.close(); channel.getConnection().close();
        // 6. be able to receive a frame in one channel, but publish onto another
        //    connection.getChannel(code);
        // 7. be able to open a new channel
        //    connection.open()
        // 8. be able to publish to a channel in another connection

        /**
         * Bayeux has a server-side Client that represent the connection with a remote client
         * onto which you can deliver().
         * But it also has server-side Channels, via Bayeux.getChannel() and also Bayeux.getClient()
         * to find a particular client.
         * OPENED can be used to send a "clientId" so that it can be later retrieved. [Is this needed ?]
         */

        /**
         * The generator cannot be at server level; the BWTPListener must have an instance of the
         * generator to allow message generation. This means that I need to pass as parameters to the
         * BWTPListener objects that are connection local such as BWTPChannel/BWTPConnection
         */

    }
}
