package org.cometd.bwtp.server;

import java.nio.channels.ClosedChannelException;

import org.cometd.bwtp.BWTPChannel;
import org.cometd.bwtp.BWTPHeaderFrame;
import org.cometd.bwtp.BWTPMessageFrame;

/**
 * @version $Revision$ $Date$
 */
public class EchoBWTPProcessor extends BWTPProcessor.Adapter
{
    @Override
    public void onHeader(BWTPChannel channel, BWTPHeaderFrame frame)
    {
        try
        {
            channel.header(frame.getHeaders());
        }
        catch (ClosedChannelException ignored)
        {
        }
    }

    @Override
    public void onMessage(BWTPChannel channel, BWTPMessageFrame frame)
    {
        try
        {
            channel.message(frame.getContent());
        }
        catch (ClosedChannelException ignored)
        {
        }
    }
}
