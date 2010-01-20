package org.cometd.server.ext;

import java.util.Map;

import org.cometd.Bayeux;
import org.cometd.Client;
import org.cometd.Extension;
import org.cometd.Message;
import org.cometd.server.ClientImpl;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;

/**
 * Acknowledged Messages extension.
 *
 * Installing this extension in a bayeux server allows it to handle the ack
 * extension if a client also supports it.
 *
 * The main role of this extension is to install the
 * {@link AcknowledgedMessagesClientExtension} on the {@link Client} instances
 * created during handshake for clients that also support the ack extension.
 *
 */
public class AcknowledgedMessagesExtension implements Extension
{
    private JSON.Literal _replyExt = new JSON.Literal("{\"ack\":true}");

    public Message rcv(Client from, Message message)
    {
        return message;
    }

    public Message rcvMeta(Client from, Message message)
    {
        return message;
    }

    public Message send(Client from, Message message)
    {
        return message;
    }

    public Message sendMeta(Client from, Message message)
    {
        String channel = message.getChannel();
        if (channel == null)
            return message;

        if (Bayeux.META_HANDSHAKE.equals(channel) && Boolean.TRUE.equals(message.get(Bayeux.SUCCESSFUL_FIELD)))
        {
            Message rcv = message.getAssociated();

            Map<String,Object> ext = rcv.getExt(false);
            boolean clientRequestedAcks = ext != null && ext.get("ack") == Boolean.TRUE;

            if (clientRequestedAcks && from != null)
            {
                Log.info("Enabled message acknowledgement for client " + from);
                from.addExtension(new AcknowledgedMessagesClientExtension(from));
                ((ClientImpl)from).setMetaConnectDeliveryOnly(true);
            }

            message.put(Bayeux.EXT_FIELD,_replyExt);
        }

        return message;
    }
}
