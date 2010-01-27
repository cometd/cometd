package org.cometd.server.ext;

import java.util.Map;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession.Extension;
import org.eclipse.jetty.util.ArrayQueue;

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

    public AcknowledgedMessagesClientExtension(ServerSession session)
    {
        _session=session;
        _queue=(ArrayQueue<ServerMessage>)session.getQueue();
        _unackedQueue=new ArrayIdQueue<ServerMessage>(8,16,session.getQueue());
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
        
        if (Channel.META_CONNECT.equals(message.getChannelId()))
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
                        // We have received an ack ID, so delete the acked
                        // messages.
                        final int s=_unackedQueue.size();
                        if (s > 0)
                        {
                            if (_unackedQueue.getAssociatedIdUnsafe(s - 1) <= acked)
                            {
                                // we can just clear the queue
                                for (int i=0; i < s; i++)
                                {
                                    final ServerMessage q=_unackedQueue.getUnsafe(i);
                                    q.decRef();
                                }
                                _unackedQueue.clear();
                            }
                            else
                            {
                                // we need to remove elements until we see unacked
                                for (int i=0; i < s; i++)
                                {
                                    if (_unackedQueue.getAssociatedIdUnsafe(0) <= acked)
                                    {
                                        final ServerMessage q=_unackedQueue.remove();
                                        q.decRef();
                                        continue;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }

                // requeue all unacked messages.
                final int cid=_unackedQueue.getCurrentId();
                final int s=_unackedQueue.size();
                for (int i=0; i < s; i++)
                {
                    if (_unackedQueue.getAssociatedIdUnsafe(0) < cid)
                        _queue.add(i,_unackedQueue.remove());
                    else
                        break;
                }
            }
        }

        return true;
    }

    /* ------------------------------------------------------------ */
    public ServerMessage send(ServerSession to, ServerMessage message)
    {
        synchronized(_session)
        {
            message.incRef();
            _unackedQueue.add(message);
        }
        return message;
    }

    /* ------------------------------------------------------------ */
    public boolean sendMeta(ServerSession to, Mutable message)
    {
        if (message.getChannelId().equals(Channel.META_CONNECT))
        {
            synchronized(_session)
            {
                Map<String,Object> ext=message.getExt(true);
                ext.put("ack",_unackedQueue.getCurrentId());
                _unackedQueue.incrementCurrentId();
            }
        }
        return true;
    }

}
    
    
