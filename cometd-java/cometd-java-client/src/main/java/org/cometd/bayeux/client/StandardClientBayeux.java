package org.cometd.bayeux.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.BayeuxException;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.CommonMessage;
import org.cometd.bayeux.Extension;
import org.cometd.bayeux.IMessage;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.MetaChannelType;
import org.cometd.bayeux.MetaMessage;
import org.cometd.bayeux.Struct;
import org.cometd.bayeux.client.transport.Transport;
import org.cometd.bayeux.client.transport.TransportException;
import org.cometd.bayeux.client.transport.TransportListener;
import org.cometd.bayeux.client.transport.TransportRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class StandardClientBayeux implements ClientBayeux, IClientSession
{
    private static final String BAYEUX_VERSION = "1.0";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final MetaChannelRegistry metaChannels = new MetaChannelRegistry();
    private final ChannelRegistry channels = new ChannelRegistry(this);
    private final TransportRegistry transports = new TransportRegistry();
    private final TransportListener transportListener = new Listener();
    private final List<Extension> extensions = new CopyOnWriteArrayList<Extension>();
    private final AtomicInteger messageIds = new AtomicInteger();
    private final ScheduledExecutorService scheduler;
    private volatile State state = State.DISCONNECTED;
    private volatile Transport transport;
    private volatile String clientId;
    private volatile Struct advice;
    private volatile ScheduledFuture<?> scheduled; // TODO cancel this when appropriate (e.g. on disconnect)

    public StandardClientBayeux(Transport... transports)
    {
        this(Executors.newSingleThreadScheduledExecutor(), transports);
    }

    public StandardClientBayeux(ScheduledExecutorService scheduler, Transport... transports)
    {
        this.scheduler = scheduler;
        for (Transport transport : transports)
            this.transports.add(transport);
    }

    public MetaChannel getMetaChannel(MetaChannelType type)
    {
        return getMutableMetaChannel(type);
    }

    protected MetaChannel.Mutable getMutableMetaChannel(MetaChannelType type)
    {
        return metaChannels.from(type);
    }

    public void handshake()
    {
        if (!isDisconnected())
            throw new IllegalStateException();

        asyncHandshake();
    }

    private void asyncHandshake()
    {
        String[] transports = this.transports.findTransportTypes(BAYEUX_VERSION);
        Transport newTransport = negotiateTransport(transports);
        transport = lifecycleTransport(transport, newTransport);
        logger.debug("Handshaking with transport {}", transport);

        MetaMessage.Mutable request = newMessage();
        request.setChannelName(MetaChannelType.HANDSHAKE.getName());
        request.put(Message.VERSION_FIELD, BAYEUX_VERSION);
        request.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, transports);

        updateState(State.HANDSHAKING);
        send(request, true);
    }

    private IMessage.Mutable newMessage()
    {
        return transport.newMessage();
    }

    private void updateState(State newState)
    {
        logger.debug("State change: {} -> {}", state, newState);
        this.state = newState;
    }

    private Transport lifecycleTransport(Transport oldTransport, Transport newTransport)
    {
        if (oldTransport != null)
        {
            oldTransport.removeListener(transportListener);
            oldTransport.destroy();
        }
        newTransport.addListener(transportListener);
        newTransport.init();
        return newTransport;
    }

    public void addExtension(Extension extension)
    {
        extensions.add(extension);
    }

    public void removeExtension(Extension extension)
    {
        extensions.remove(extension);
    }

    public Channel getChannel(String channelName)
    {
        return getMutableChannel(channelName);
    }

    private Channel.Mutable getMutableChannel(String channelName)
    {
        return channels.from(channelName, true);
    }

    public void batch(Runnable batch)
    {
        // TODO
    }

    public void disconnect()
    {
        if (isDisconnected())
            throw new IllegalStateException();

        MetaMessage.Mutable message = newMessage();
        message.setChannelName(MetaChannelType.DISCONNECT.getName());

        updateState(State.DISCONNECTING);
        send(message, true);
    }

    public String getClientId()
    {
        return clientId;
    }

    private Transport negotiateTransport(String[] requestedTransports)
    {
        Transport transport = transports.negotiate(BAYEUX_VERSION, requestedTransports);
        if (transport == null)
            throw new TransportException("Could not negotiate transport: requested " +
                    Arrays.toString(requestedTransports) +
                    ", available " +
                    Arrays.toString(transports.findTransportTypes(BAYEUX_VERSION)));
        return transport;
    }

    protected void send(CommonMessage.Mutable message, boolean meta)
    {
        message = applyOutgoingExtensions(message, meta);
        if (message != null)
        {
            // TODO: handle batches
            transport.send(message);
        }
    }

    private CommonMessage.Mutable applyOutgoingExtensions(CommonMessage.Mutable message, boolean meta)
    {
        for (Extension extension : extensions)
        {
            try
            {
                if (meta)
                    message = extension.metaOutgoing((MetaMessage.Mutable)message);
                else
                    message = extension.outgoing((Message.Mutable)message);

                if (message == null)
                {
                    logger.debug("Extension {} signalled to skip message {}", extension, message);
                    return null;
                }
            }
            catch (Exception x)
            {
                logger.debug("Exception while invoking extension " + extension, x);
            }
        }
        return message;
    }

    protected void receive(List<CommonMessage.Mutable> messages)
    {
        List<CommonMessage.Mutable> processed = applyIncomingExtensions(messages);

        for (CommonMessage message : processed)
        {
            advice = message.getAdvice();

            String channelName = message.getChannelName();
            if (channelName == null)
                // TODO: call a listener method ? Discard the message ?
                throw new BayeuxException();

            MetaChannelType type = MetaChannelType.from(channelName);
            if (type == null)
            {
                Message msg = (Message)message;
                Boolean successful = (Boolean)msg.get(Message.SUCCESSFUL_FIELD);
                if (successful != null && successful)
                    processMessage(msg);
                else
                    processUnsuccessful(message);
            }
            else if (type == MetaChannelType.HANDSHAKE)
            {
                if (state != State.HANDSHAKING)
                    // TODO: call a listener method ? Discard the message ?
                    throw new BayeuxException();

                MetaMessage metaMessage = (MetaMessage)message;
                if (metaMessage.isSuccessful())
                    processHandshake(metaMessage);
                else
                    processUnsuccessful(metaMessage);
            }
            else if (type == MetaChannelType.CONNECT)
            {
                if (state != State.CONNECTED && state != State.DISCONNECTING)
                    // TODO: call a listener method ? Discard the message ?
                    throw new BayeuxException();

                MetaMessage metaMessage = (MetaMessage)message;
                if (metaMessage.isSuccessful())
                    processConnect(metaMessage);
                else
                    processUnsuccessful(metaMessage);
            }
            else if (type == MetaChannelType.DISCONNECT)
            {
                if (state != State.DISCONNECTING)
                    // TODO: call a listener method ? Discard the message ?
                    throw new BayeuxException();

                MetaMessage metaMessage = (MetaMessage)message;
                if (metaMessage.isSuccessful())
                    processDisconnect(metaMessage);
                else
                    processUnsuccessful(metaMessage);
            }
            else
            {
                throw new BayeuxException();
            }
        }
    }

    private List<CommonMessage.Mutable> applyIncomingExtensions(List<CommonMessage.Mutable> metaMessages)
    {
        // TODO: can be optimized by using the same list
        List<CommonMessage.Mutable> result = new ArrayList<CommonMessage.Mutable>();
        for (CommonMessage.Mutable message : metaMessages)
        {
            for (Extension extension : extensions)
            {
                try
                {
                    if (message instanceof MetaMessage.Mutable)
                        message = extension.metaIncoming((MetaMessage.Mutable)message);
                    else if (message instanceof Message.Mutable)
                        message = extension.incoming((Message.Mutable)message);

                    if (message == null)
                    {
                        logger.debug("Extension {} signalled to skip message {}", extension, message);
                        break;
                    }
                }
                catch (Exception x)
                {
                    logger.debug("Exception while invoking extension " + extension, x);
                }
            }
            if (message != null)
                result.add(message);
        }
        return result;
    }

    protected void processHandshake(MetaMessage handshake)
    {
        // Renegotiate transport
        Transport newTransport = transports.negotiate(BAYEUX_VERSION, (String[])handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD));
        if (newTransport == null)
        {
            // TODO: notify and stop
            throw new BayeuxException();
        }
        else if (newTransport != transport)
        {
            transport = lifecycleTransport(transport, newTransport);
        }

        updateState(State.CONNECTED);
        clientId = handshake.getClientId();

        metaChannels.notifySuscribers(getMutableMetaChannel(MetaChannelType.HANDSHAKE), handshake);

        // TODO: internal batch ?

        followAdvice();
    }

    protected void processConnect(MetaMessage metaMessage)
    {
        metaChannels.notifySuscribers(getMutableMetaChannel(MetaChannelType.CONNECT), metaMessage);
        followAdvice();
    }

    protected void processMessage(Message message)
    {
        channels.notifySubscribers(getMutableChannel(message.getChannelName()), message);
    }

    protected void processDisconnect(MetaMessage metaMessage)
    {
        metaChannels.notifySuscribers(getMutableMetaChannel(MetaChannelType.DISCONNECT), metaMessage);
    }

    protected void processUnsuccessful(CommonMessage metaMessage)
    {
        // TODO
    }

    private void followAdvice()
    {
        if (advice != null)
        {
            String action = (String)advice.get(Message.RECONNECT_FIELD);
            if (Message.RECONNECT_RETRY_VALUE.equals(action))
            {
                // Must connect, follow timings in the advice
                Number intervalNumber = (Number)advice.get(Message.INTERVAL_FIELD);
                if (intervalNumber != null)
                {
                    long interval = intervalNumber.longValue();
                    if (interval < 0L)
                        interval = 0L;
                    scheduled = scheduler.schedule(new Runnable()
                    {
                        public void run()
                        {
                            asyncConnect();
                        }
                    }, interval, TimeUnit.MILLISECONDS);
                }
            }
            else if (Message.RECONNECT_HANDSHAKE_VALUE.equals(action))
            {
                // TODO:
                throw new BayeuxException();
            }
            else if (Message.RECONNECT_NONE_VALUE.equals(action))
            {
                // Do nothing
                // TODO: sure there is nothing more to do ?
            }
            else
            {
                logger.info("Reconnect action {} not supported in advice {}", action, advice);
            }
        }
    }

    private void asyncConnect()
    {
        logger.debug("Connecting with transport {}", transport);
        MetaMessage.Mutable request = newMessage();
        request.setId(newMessageId());
        request.setClientId(clientId);
        request.setChannelName(MetaChannelType.CONNECT.getName());
        request.put(Message.CONNECTION_TYPE_FIELD, transport.getType());
        send(request, true);
    }

    private String newMessageId()
    {
        return String.valueOf(messageIds.incrementAndGet());
    }

    private class Listener extends TransportListener.Adapter
    {
        @Override
        public void onMessages(List<CommonMessage.Mutable> messages)
        {
            receive(messages);
        }
    }

    private boolean isDisconnected()
    {
        return state == State.DISCONNECTED;
    }

    public void subscribe(Channel channel)
    {
        logger.debug("Subscribing to channel {}", channel);
        MetaMessage.Mutable request = newMessage();
        request.setId(newMessageId());
        request.setClientId(clientId);
        request.setChannelName(MetaChannelType.SUBSCRIBE.getName());
        request.put(Message.SUBSCRIPTION_FIELD, channel.getName());
        send(request, true);
    }

    public void unsubscribe(Channel channel)
    {
        logger.debug("Unsubscribing from channel {}", channel);
        MetaMessage.Mutable request = newMessage();
        request.setId(newMessageId());
        request.setClientId(clientId);
        request.setChannelName(MetaChannelType.UNSUBSCRIBE.getName());
        request.put(Message.SUBSCRIPTION_FIELD, channel.getName());
        send(request, true);
    }

    public void publish(Channel channel, Object data)
    {
        Message.Mutable message = newMessage();
        message.setId(newMessageId());
        message.setClientId(clientId);
        message.setChannelName(channel.getName());
        message.setData(data);
        send(message, false);
    }

    private enum State
    {
        HANDSHAKING, CONNECTED, DISCONNECTING, DISCONNECTED
    }
}
