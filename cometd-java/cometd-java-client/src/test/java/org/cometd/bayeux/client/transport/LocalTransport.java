package org.cometd.bayeux.client.transport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public void send(final MetaMessage.Mutable... metaMessages)
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
        List<MetaMessage.Mutable> responses = new ArrayList<MetaMessage.Mutable>();
        for (MetaMessage metaMessage : metaMessages)
        {
            switch (metaMessage.getMetaChannel().getType())
            {
                case HANDSHAKE:
                    responses.add(processHandshake(metaMessage));
                    break;
                case CONNECT:
                    responses.add(processConnect(metaMessage));
                    break;
                case DISCONNECT:
                    responses.add(processDisconnect(metaMessage));
                    break;
                default:
                    throw new TransportException();
            }
        }
        notifyMetaMessages(responses.toArray(new MetaMessage.Mutable[responses.size()]));
    }

    private MetaMessage.Mutable processHandshake(MetaMessage request)
    {
        MetaMessage.Mutable response = newMetaMessage(null);
        response.setAssociated(request);
        response.setMetaChannel(request.getMetaChannel());
        response.setSuccessful(true);
        response.setId(request.getId());
        response.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, request.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD));
        response.setClientId(String.valueOf(clientIds.incrementAndGet()));
        Map<String, Object> advice = new HashMap<String, Object>();
        advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
        advice.put(Message.INTERVAL_FIELD, 0L);
        response.setAdvice(advice);
        return response;
    }

    private MetaMessage.Mutable processConnect(MetaMessage request)
    {
        MetaMessage.Mutable response = newMetaMessage(null);
        response.setAssociated(request);
        response.setMetaChannel(request.getMetaChannel());
        response.setSuccessful(true);
        response.setId(request.getId());
        Map<String, Object> advice = new HashMap<String, Object>();
        advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
        advice.put(Message.INTERVAL_FIELD, 5000L);
        response.setAdvice(advice);
        return response;
    }

    private MetaMessage.Mutable processDisconnect(MetaMessage request)
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
