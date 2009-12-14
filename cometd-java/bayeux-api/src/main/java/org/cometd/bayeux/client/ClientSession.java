package org.cometd.bayeux.client;

import java.util.EventListener;

import org.cometd.bayeux.Client;



/**
 * @version $Revision$ $Date: 2009-12-08 09:42:45 +1100 (Tue, 08 Dec 2009) $
 */
public interface ClientSession extends Client
{
    ChannelClient getChannel(String channelName);

    void batch(Runnable batch);

    void disconnect();
        
    
    void addListener(Listener listener);

    interface Listener extends EventListener
    {};
}
