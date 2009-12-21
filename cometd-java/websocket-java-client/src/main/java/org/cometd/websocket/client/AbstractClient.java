package org.cometd.websocket.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cometd.websocket.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public abstract class AbstractClient implements Client
{
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    public void addListener(final Listener listener)
    {
        listeners.add(listener);
    }

    public void removeListener(Listener listener)
    {
        listeners.remove(listener);
    }

    protected void notifyOpen(Map<String, String> headers)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onOpen(headers);
            }
            catch (Throwable xx)
            {
                logger.debug("Exception caught while notifying listener " + listener, xx);
            }
        }
    }

    protected void notifyMessage(Message message)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onMessage(message);
            }
            catch (Throwable x)
            {
                logger.debug("Exception caught while notifying listener " + listener, x);
            }
        }
    }

    protected void notifyClose()
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onClose();
            }
            catch (Throwable x)
            {
                logger.debug("Exception caught while notifying listener " + listener, x);
            }
        }
    }

    protected void notifyConnectException(Throwable x)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onConnectException(x);
            }
            catch (Exception xx)
            {
                logger.debug("Exception caught while notifying listener " + listener, xx);
            }
        }
    }

    protected void notifyException(Throwable x)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onException(x);
            }
            catch (Exception xx)
            {
                logger.debug("Exception caught while notifying listener " + listener, xx);
            }
        }
    }

    protected void notifyProtocolError()
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onProtocolError();
            }
            catch (Exception xx)
            {
                logger.debug("Exception caught while notifying listener " + listener, xx);
            }
        }
    }
}
