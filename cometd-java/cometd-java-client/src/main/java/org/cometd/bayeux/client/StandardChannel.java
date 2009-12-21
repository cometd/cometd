package org.cometd.bayeux.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @version $Revision$ $Date$
 */
public class StandardChannel implements Channel
{
    private final List<MessageListener> subscribers = new CopyOnWriteArrayList<MessageListener>();
    private final IClientSession session;

    public StandardChannel(IClientSession session)
    {
        this.session = session;
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
}
