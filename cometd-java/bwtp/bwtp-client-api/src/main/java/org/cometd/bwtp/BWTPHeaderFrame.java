package org.cometd.bwtp;

import java.util.Map;

/**
 * @version $Revision$ $Date$
 */
public final class BWTPHeaderFrame extends BWTPFrame
{
    private final BWTPActionType action;
    private final String arguments;
    private final Map<String, String> headers;

    public BWTPHeaderFrame(String channel, int frameSize, BWTPActionType action, String arguments, Map<String, String> headers)
    {
        super(channel, frameSize);
        this.action = action;
        this.arguments = arguments;
        this.headers = headers;
    }

    @Override
    public BWTPFrameType getType()
    {
        return BWTPFrameType.HEADER;
    }

    public BWTPActionType getAction()
    {
        return action;
    }

    public String getArguments()
    {
        return arguments;
    }

    public Map<String, String> getHeaders()
    {
        return headers;
    }
}
