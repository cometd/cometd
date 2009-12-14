package org.cometd.bayeux.server;

import java.util.EventListener;
import java.util.Set;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.client.Subscription;


public interface ServerChannel extends Channel
{
    boolean isMeta();
    boolean isLazy();
    boolean isPersistent();
    boolean isBroadcast();  // !meta and !service

    Set<Subscription> getSubscriptions();
    void addDataFilter(DataFilter filter);
    void addListener(Listener listener);
    
    interface Listener extends EventListener
    {};
}
