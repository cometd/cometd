package org.cometd.bayeux.client.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.CommonMessage;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.MetaChannelType;
import org.cometd.bayeux.MetaMessage;
import org.cometd.bayeux.StandardStruct;
import org.cometd.bayeux.Struct;

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

    public void send(final CommonMessage.Mutable... messages)
    {
        new Thread(new Runnable()
        {
            public void run()
            {
                process(messages);
            }
        }).start();
    }

    private void process(CommonMessage... messages)
    {
        List<CommonMessage.Mutable> responses = new ArrayList<CommonMessage.Mutable>();
        for (CommonMessage message : messages)
        {
            MetaChannelType type = MetaChannelType.from(message.getChannelName());
            if (type == null)
            {
                Message.Mutable response = processPublish(message);
                if (response != null)
                    responses.add(response);
            }
            else if (type == MetaChannelType.HANDSHAKE)
            {
                responses.add(processHandshake(message));
            }
            else if (type == MetaChannelType.CONNECT)
            {
                responses.add(processConnect(message));
            }
            else if (type == MetaChannelType.SUBSCRIBE)
            {
                responses.add(processSubscribe(message));
            }
            else if (type == MetaChannelType.UNSUBSCRIBE)
            {
                responses.add(processUnsubscribe(message));
            }
            else if (type == MetaChannelType.DISCONNECT)
            {
                responses.add(processDisconnect(message));
            }
            else
            {
                throw new TransportException();
            }
        }
        notifyMessages(responses);
    }

    private MetaMessage.Mutable processHandshake(CommonMessage request)
    {
        MetaMessage.Mutable response = newMessage(null);
        response.setId(request.getId());
        response.setChannelName(request.getChannelName());
        response.setSuccessful(true);
        response.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, request.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD));
        response.setClientId(String.valueOf(clientIds.incrementAndGet()));
        Struct.Mutable advice = new StandardStruct();
        advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
        advice.put(Message.INTERVAL_FIELD, 0L);
        response.setAdvice(advice);
        return response;
    }

    private MetaMessage.Mutable processConnect(CommonMessage request)
    {
        MetaMessage.Mutable response = newMessage(null);
        response.setId(request.getId());
        response.setChannelName(request.getChannelName());
        response.setSuccessful(true);
        Struct.Mutable advice = new StandardStruct();
        advice.put(Message.RECONNECT_FIELD, Message.RECONNECT_RETRY_VALUE);
        advice.put(Message.INTERVAL_FIELD, 5000L);
        response.setAdvice(advice);
        return response;
    }

    private MetaMessage.Mutable processSubscribe(CommonMessage request)
    {
        MetaMessage.Mutable response = newMessage(null);
        response.setId(request.getId());
        response.setClientId(request.getClientId());
        response.setChannelName(request.getChannelName());
        response.setSuccessful(true);
        response.put(Message.SUBSCRIPTION_FIELD, request.get(Message.SUBSCRIPTION_FIELD));
        return response;
    }

    private MetaMessage.Mutable processUnsubscribe(CommonMessage request)
    {
        MetaMessage.Mutable response = newMessage(null);
        response.setId(request.getId());
        response.setClientId(request.getClientId());
        response.setChannelName(request.getChannelName());
        response.setSuccessful(true);
        response.put(Message.SUBSCRIPTION_FIELD, request.get(Message.SUBSCRIPTION_FIELD));
        return response;
    }

    private Message.Mutable processPublish(CommonMessage request)
    {
        Message.Mutable response = newMessage();
        response.setId(request.getId());
        response.setClientId(request.getClientId());
        response.setChannelName(request.getChannelName());
        response.put(Message.SUCCESSFUL_FIELD, true);
        return response;
    }

    private MetaMessage.Mutable processDisconnect(CommonMessage request)
    {
        MetaMessage.Mutable response = newMessage(null);
        response.setId(request.getId());
        response.setClientId(request.getClientId());
        response.setChannelName(request.getChannelName());
        response.setSuccessful(true);
        return response;
    }
}
