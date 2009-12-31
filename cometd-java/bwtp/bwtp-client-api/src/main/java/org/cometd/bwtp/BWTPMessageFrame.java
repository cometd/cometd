package org.cometd.bwtp;

/**
 * @version $Revision$ $Date$
 */
public final class BWTPMessageFrame extends BWTPFrame
{
    private final int messageSize;
    private final byte[] content;

    public BWTPMessageFrame(String channel, int frameSize, int messageSize, byte[] content)
    {
        super(channel, frameSize);
        this.messageSize = messageSize;
        this.content = content;
    }

    @Override
    public BWTPFrameType getType()
    {
        return BWTPFrameType.MESSAGE;
    }

    public int getMessageSize()
    {
        return messageSize;
    }

    public byte[] getContent()
    {
        return content;
    }
}
