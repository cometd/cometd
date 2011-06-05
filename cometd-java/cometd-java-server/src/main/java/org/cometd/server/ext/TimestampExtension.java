package org.cometd.server.ext;

import java.util.TimeZone;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.eclipse.jetty.util.DateCache;

public class TimestampExtension implements Extension
{
    private final DateCache _dateCache;

    public TimestampExtension()
    {
        _dateCache=new DateCache();
        _dateCache.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public TimestampExtension(String format)
    {
        _dateCache=new DateCache(format);
        _dateCache.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public TimestampExtension(String format, TimeZone tz)
    {
        _dateCache=new DateCache(format);
        _dateCache.setTimeZone(tz);
    }


    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.server.BayeuxServer.Extension#rcv(org.cometd.bayeux.server.ServerSession, org.cometd.bayeux.server.ServerMessage.Mutable)
     */
    public boolean rcv(ServerSession from, Mutable message)
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.server.BayeuxServer.Extension#rcvMeta(org.cometd.bayeux.server.ServerSession, org.cometd.bayeux.server.ServerMessage.Mutable)
     */
    public boolean rcvMeta(ServerSession from, Mutable message)
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.server.BayeuxServer.Extension#send(org.cometd.bayeux.server.ServerMessage.Mutable)
     */
    public boolean send(ServerSession from, ServerSession to, Mutable message)
    {
        message.put(Message.TIMESTAMP_FIELD,_dateCache.format(System.currentTimeMillis()));
        return true;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.server.BayeuxServer.Extension#sendMeta(org.cometd.bayeux.server.ServerSession, org.cometd.bayeux.server.ServerMessage.Mutable)
     */
    public boolean sendMeta(ServerSession to, Mutable message)
    {
        message.put(Message.TIMESTAMP_FIELD,_dateCache.format(System.currentTimeMillis()));
        return true;
    }
}
