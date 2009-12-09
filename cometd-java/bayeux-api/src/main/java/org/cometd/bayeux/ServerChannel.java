package org.cometd.bayeux;

import java.util.Set;


public interface ServerChannel extends Channel
{
    boolean isMeta();
    boolean isLazy();
    boolean isPersistent();
    boolean isBroadcast();  // !meta and !service

    Set<Subscription> getSubscriptions();
    void addDataFilter(DataFilter filter);
    void addListener(ChannelListener listener);
}
