package org.cometd.server.ext;

import java.util.Map;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession.Extension;
import org.eclipse.jetty.util.ArrayQueue;
import org.eclipse.jetty.util.log.Log;

/**
 * Acknowledged Message Client extension.
 * 
 * Tracks the batch id of messages sent to a client.
 * 
 */
public class AcknowledgedMessagesClientExtension implements Extension
{
    private final ServerSession _session;
    private final ArrayQueue<ServerMessage> _queue;
    private final ArrayIdQueue<ServerMessage> _unackedQueue;
    private long _lastAck;

    public AcknowledgedMessagesClientExtension(ServerSession session)
    {
        _session=session;
        _queue=(ArrayQueue<ServerMessage>)session.getQueue();
        _unackedQueue=new ArrayIdQueue<ServerMessage>(16,32,session.getQueue());
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

                synchronized(_queue)
                {
                    Long acked=(Long)ext.get("ack");
                    if (acked != null)
                    {                        
                        if (acked.longValue()<=_lastAck)
                        {
                            Log.debug(session+" lost ACK "+acked.longValue()+"<="+_lastAck);
                            _queue.clear();
                            _queue.addAll(_unackedQueue);
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
        synchronized(_queue)
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
            synchronized(_queue)
            {
                Map<String,Object> ext=message.getExt(true);
                ext.put("ack",_unackedQueue.getCurrentId());
                _unackedQueue.incrementCurrentId();
            }
        }
        return true;
    }

}
    
    
