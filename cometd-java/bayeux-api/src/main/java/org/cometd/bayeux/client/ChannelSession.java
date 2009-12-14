package org.cometd.bayeux.client;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Client;
import org.cometd.bayeux.MessageListener;


public interface ChannelSession extends Channel
{
    ClientSession getClientSession();
    Subscription subscribe(MessageListener listener);
    void publish(Object data);
}
