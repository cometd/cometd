package org.cometd.server.ext;

import java.util.List;
import java.util.Map;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerSession.Extension;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Acknowledged Message Client extension.
 *
 * Tracks the batch id of messages sent to a client.
 *
 */
public class AcknowledgedMessagesClientExtension implements Extension
{
    private final Logger _logger = Log.getLogger(getClass().getName());
    private final ServerSessionImpl _session;
    private final Object _lock;
    private final ArrayIdQueue<ServerMessage> _unackedQueue;
    private long _lastAck;

    public AcknowledgedMessagesClientExtension(ServerSession session)
    {
        _session=(ServerSessionImpl)session;
        _lock=_session.getLock();

        List<ServerMessage> copy = _session.takeQueue();
        _session.replaceQueue(copy);
        _unackedQueue=new ArrayIdQueue<ServerMessage>(16,32,copy);
        _unackedQueue.setCurrentId(1);
    }

    /* ------------------------------------------------------------ */
    public boolean rcv(ServerSession from, Mutable message)
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    public boolean rcvMeta(ServerSession session, Mutable message)
    {
        if (Channel.META_CONNECT.equals(message.getChannel()))
        {
            Map<String,Object> ext=message.getExt(false);
            if (ext != null)
            {
                assert session==_session;

                synchronized(_lock)
                {
                    Long acked=(Long)ext.get("ack");
                    if (acked != null)
                    {
                        if (acked.longValue()<=_lastAck)
                        {
                            _logger.debug(session + " lost ACK " + acked.longValue() + "<=" + _lastAck);
                            _session.replaceQueue(_unackedQueue);
                        }
                        else
                        {
                            _lastAck=acked.longValue();

                            // We have received an ack ID, so delete the acked
                            // messages.
                            final int s=_unackedQueue.size();
                            if (s > 0)
                            {
                                if (_unackedQueue.getAssociatedIdUnsafe(s - 1) <= acked)
                                {
                                    // we can just clear the queue
                                    _unackedQueue.clear();
                                }
                                else
                                {
                                    // we need to remove elements until we see unacked
                                    for (int i=0; i < s; i++)
                                    {
                                        final long a=_unackedQueue.getAssociatedIdUnsafe(0);
                                        if (a <= acked)
                                        {
                                            final ServerMessage q=_unackedQueue.remove();
                                            continue;
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return true;
    }

    /* ------------------------------------------------------------ */
    public ServerMessage send(ServerSession to, ServerMessage message)
    {
        synchronized(_lock)
        {
            _unackedQueue.add(message);
        }

        return message;
    }

    /* ------------------------------------------------------------ */
    public boolean sendMeta(ServerSession to, Mutable message)
    {
        if (message.getChannel().equals(Channel.META_CONNECT))
        {
            synchronized(_lock)
            {
                Map<String,Object> ext=message.getExt(true);
                ext.put("ack",_unackedQueue.getCurrentId());
                _unackedQueue.incrementCurrentId();
            }
        }
        return true;
    }

}


