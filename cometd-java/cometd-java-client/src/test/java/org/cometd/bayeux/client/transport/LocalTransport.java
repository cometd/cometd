package org.cometd.bayeux.client.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.MetaMessage;

/**
 * @version $Revision$ $Date$
 */
public class LocalTransport extends AbstractTransport
{
    private final AtomicInteger clientIds = new AtomicInteger();

    public String getType()
    {
        return "local";
    }

    public boolean accept(String bayeuxVersion)
    {
        return true;
    }

    public void init()
    {
    }

    public void send(final MetaMessage... metaMessages)
    {
        new Thread(new Runnable()
        {
            public void run()
            {
                process(metaMessages);
            }
        }).start();
    }

    private void process(MetaMessage... metaMessages)
    {
        List<MetaMessage> responses = new ArrayList<MetaMessage>();
        for (MetaMessage metaMessage : metaMessages)
        {
            switch (metaMessage.getMetaChannel().getType())
            {
                case HANDSHAKE:
                    responses.add(processHandshake(metaMessage));
                    break;
                case DISCONNECT:
                    responses.add(processDisconnect(metaMessage));
                    break;
                default:
                    throw new TransportException();
            }
        }
        notifyMetaMessages(responses.toArray(new MetaMessage[responses.size()]));
    }

    private MetaMessage processHandshake(MetaMessage request)
    {
        MetaMessage.Mutable response = newMetaMessage(null);
        response.setAssociated(request);
        response.setMetaChannel(request.getMetaChannel());
        response.setSuccessful(true);
        response.setId(request.getId());
        response.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, request.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD));
        response.setClientId(String.valueOf(clientIds.incrementAndGet()));
        return response;
    }

    private MetaMessage processDisconnect(MetaMessage request)
    {
        MetaMessage.Mutable response = newMetaMessage(null);
        response.setAssociated(request);
        response.setMetaChannel(request.getMetaChannel());
        response.setSuccessful(true);
        response.setId(request.getId());
        response.setClientId(request.getClientId());
        return response;
    }
}
