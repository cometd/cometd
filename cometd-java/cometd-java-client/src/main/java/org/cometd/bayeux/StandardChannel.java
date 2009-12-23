package org.cometd.bayeux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.bayeux.client.IClientSession;

/**
 * @version $Revision$ $Date$
 */
public class StandardChannel implements Channel.Mutable
{
    private final List<MessageListener> subscribers = new CopyOnWriteArrayList<MessageListener>();
    private final IClientSession session;
    private final String name;

    public StandardChannel(IClientSession session, String name)
    {
        this.session = session;
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    public void subscribe(MessageListener listener)
    {
        boolean wasEmpty = subscribers.isEmpty();
        subscribers.add(listener);
        if (wasEmpty)
            session.subscribe(this);
    }

    public void unsubscribe(MessageListener listener)
    {
        subscribers.remove(listener);
        boolean isEmpty = subscribers.isEmpty();
        if (isEmpty)
            session.unsubscribe(this);
    }

    public void publish(Object data)
    {
        session.publish(this, data);
    }

    public void notifySubscribers(Message message)
    {
        for (MessageListener listener : subscribers)
            listener.onMessage(message);
    }
}
