package org.cometd.bayeux;

public interface Channel
{
    String getChannelId();
    boolean isMetaChannel();
}
