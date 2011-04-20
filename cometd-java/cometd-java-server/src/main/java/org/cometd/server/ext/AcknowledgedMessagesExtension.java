package org.cometd.server.ext;

import java.util.Map;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.BayeuxServer.Extension;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
    private final Logger _logger = Log.getLogger(getClass().getName());
    private final JSON.Literal _replyExt = new JSON.Literal("{\"ack\":true}");

    /* ------------------------------------------------------------ */
    public boolean rcv(ServerSession from, Mutable message)
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    public boolean rcvMeta(ServerSession from, Mutable message)
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    public boolean send(ServerSession from, ServerSession to, Mutable message)
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    public boolean sendMeta(ServerSession to, Mutable message)
    {
        String channel = message.getChannel();
        if (channel == null)
            return true;

        if (Channel.META_HANDSHAKE.equals(channel) && Boolean.TRUE.equals(message.get(Message.SUCCESSFUL_FIELD)))
        {
            Message rcv = message.getAssociated();

            Map<String,Object> ext = rcv.getExt();
            boolean clientRequestedAcks = ext != null && ext.get("ack") == Boolean.TRUE;

            if (clientRequestedAcks && to != null)
            {
                _logger.info("Enabled message acknowledgement for client " + to);
                to.addExtension(new AcknowledgedMessagesClientExtension(to));
                ((ServerSessionImpl)to).setMetaConnectDeliveryOnly(true);
            }

            Map<String,Object> mext=message.getExt();
            if (mext!=null)
                mext.put("ack",Boolean.TRUE);
            else
                message.put(Message.EXT_FIELD,_replyExt);
        }

        return true;
    }

}
