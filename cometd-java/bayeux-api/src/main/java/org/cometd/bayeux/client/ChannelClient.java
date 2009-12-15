package org.cometd.bayeux.client;


import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Client;
import org.cometd.bayeux.MessageListener;


public interface ChannelClient
{
    Channel getChannel();
    Client getClient();
    Subscription subscribe(MessageListener listener);
    void publish(Object data);

    
}
