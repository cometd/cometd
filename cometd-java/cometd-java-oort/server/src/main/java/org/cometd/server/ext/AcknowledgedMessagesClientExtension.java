package org.cometd.server.ext;

import java.util.Map;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;
import org.cometd.server.MessageImpl;
import org.eclipse.jetty.util.ArrayQueue;

/**
 * Acknowledged Message Client extension.
 * 
 * Tracks the batch id of messages sent to a client.
 * 
 */
public class AcknowledgedMessagesClientExtension implements Extension
{
    private final Client _client;
    private final ArrayIdQueue<Message> _unackedQueue;

    public AcknowledgedMessagesClientExtension(Client client)
    {
        _client=client;
        _unackedQueue=new ArrayIdQueue<Message>(8,16,client);
        _unackedQueue.setCurrentId(1);
    }

    public Message rcv(Client from, Message message)
    {
        return message;
    }

    /**
     * Handle received meta messages. Looks for meta/connect messages with
     * ext/ack fields. If present, delete all messages that have been acked and
     * requeue messages that have not been acked.
     */
    public Message rcvMeta(Client from, Message message)
    {
        if (message.getChannel().equals(Bayeux.META_CONNECT))
        {
            synchronized(_client)
            {
                Map<String,Object> ext=message.getExt(false);
                if (ext != null)
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
                                    final Message q=_unackedQueue.getUnsafe(i);
                                    if (q instanceof MessageImpl)
                                        ((MessageImpl)q).decRef();
                                }
                                _unackedQueue.clear();
                            }
                            else
                            {
                                // we need to remove elements until we see
                                // unacked
                                for (int i=0; i < s; i++)
                                {
                                    if (_unackedQueue.getAssociatedIdUnsafe(0) <= acked)
                                    {
                                        final Message q=_unackedQueue.remove();
                                        if (q instanceof MessageImpl)
                                            ((MessageImpl)q).decRef();
                                        continue;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }

                // requeue all unacked messages.
                final ArrayQueue<Message> messages=(ArrayQueue)from.getQueue();
                final int cid=_unackedQueue.getCurrentId();
                final int s=_unackedQueue.size();
                for (int i=0; i < s; i++)
                {
                    if (_unackedQueue.getAssociatedIdUnsafe(0) < cid)
                        messages.add(i,_unackedQueue.remove());
                    else
                        break;
                }
            }
        }

        return message;
    }

    public Message send(Client from, Message message)
    {
        synchronized(_client)
        {
            _unackedQueue.add(message);
            // prevent the message from being erased
            ((MessageImpl)message).incRef();
        }
        return message;
    }

    public Message sendMeta(Client from, Message message)
    {
        if (message.getChannel().equals(Bayeux.META_CONNECT))
        {
            synchronized(_client)
            {
                Map<String,Object> ext=message.getExt(true);
                ext.put("ack",_unackedQueue.getCurrentId());
                _unackedQueue.incrementCurrentId();
            }
        }
        return message;
    }
}
