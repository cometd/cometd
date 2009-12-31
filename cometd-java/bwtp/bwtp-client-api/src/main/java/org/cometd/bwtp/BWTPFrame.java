package org.cometd.bwtp;

/**
 * @version $Revision$ $Date$
 */
public abstract class BWTPFrame
{
    private final String channel;
    private final int frameSize;

    protected BWTPFrame(String channel, int frameSize)
    {
        this.channel = channel;
        this.frameSize = frameSize;
    }

    public String getChannelId()
    {
        return channel;
    }

    public int getFrameSize()
    {
        return frameSize;
    }

    public abstract BWTPFrameType getType();
}
