package org.cometd.common;

import org.cometd.bayeux.Bayeux;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Session;
import org.cometd.bayeux.Bayeux.Extension;
import org.cometd.bayeux.Message.Mutable;

/**
 * Adapter for the {@link Extension} interface that always returns true from callback methods.
 */
public class AbstractBayeuxExtension implements Bayeux.Extension
{
    public boolean rcv(Session session, Message.Mutable message)
    {
        return true;
    }

    public boolean rcvMeta(Session session, Message.Mutable message)
    {
        return true;
    }

    public boolean send(Session session, Message.Mutable message)
    {
        return true;
    }

    public boolean sendMeta(Session session, Message.Mutable message)
    {
        return true;
    }
}