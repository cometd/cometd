package org.cometd.bayeux.client;

import bayeux.Channel;
import bayeux.ChannelSubscription;
import bayeux.Message;
import bayeux.MessageListener;

/**
 * @version $Revision$ $Date$
 */
public class StandardChannel implements Channel
{
    private final IClient client;

    public StandardChannel(IClient client)
    {
        this.client = client;
    }

    public ChannelSubscription subscribe(final MessageListener listener)
    {
/*
        listeners.add(listener);
        Message message = null;
        client.send(message);
        return new ChannelSubscription()
        {
            public void unsubscribe()
            {
                StandardChannel.this.unsubscribe(listener);
            }
        };
*/
        return null;
    }

    public void publish(Object data)
    {
        Message message = null;
        client.send(message);
    }
}
