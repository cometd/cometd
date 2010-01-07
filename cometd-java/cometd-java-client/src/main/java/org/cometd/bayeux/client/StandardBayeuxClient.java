package org.cometd.bayeux.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.transport.Transport;
import org.cometd.bayeux.client.transport.TransportException;
import org.cometd.bayeux.client.transport.TransportListener;
import org.cometd.bayeux.client.transport.TransportRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class StandardBayeuxClient implements BayeuxClient
{
    private static final String BAYEUX_VERSION = "1.0";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final List<Extension> extensions = new CopyOnWriteArrayList<Extension>();
    private final List<BayeuxListener> listeners = new CopyOnWriteArrayList<BayeuxListener>();
    private final TransportRegistry transports = new TransportRegistry();
    private final TransportListener transportListener = new Listener();
    private final AtomicInteger messageIds = new AtomicInteger();
    private final ScheduledExecutorService scheduler;
    private volatile State state = State.DISCONNECTED;
    private volatile Transport transport;
    private volatile String clientId;
    private volatile Map<String,Object> advice;
    private volatile ScheduledFuture<?> task;

    public StandardBayeuxClient(Transport... transports)
    {
        this(Executors.newSingleThreadScheduledExecutor(), transports);
    }

    public StandardBayeuxClient(ScheduledExecutorService scheduler, Transport... transports)
    {
        this.scheduler = scheduler;
        for (Transport transport : transports)
            this.transports.add(transport);
    }

    public String getId()
    {
        return clientId;
    }

    public void addExtension(Extension extension)
    {
        extensions.add(extension);
    }

    public void removeExtension(Extension extension)
    {
        extensions.remove(extension);
    }

    public void addListener(BayeuxListener listener) throws IllegalArgumentException
    {
        if (!(listener instanceof BayeuxClientListener))
            throw new IllegalArgumentException("Wrong listener type, must be instance of " + BayeuxClientListener.class.getName());
        listeners.add(listener);
    }

    public void removeListener(BayeuxListener listener)
    {
        listeners.remove(listener);
    }

    public void addListener(SessionListener listener)
    {
        // TODO
    }

    public void removeListener(SessionListener listener)
    {
        // TODO
    }

    public void handshake(boolean async) throws IOException
    {
        if (async)
            asyncHandshake();
        else
            syncHandshake();
    }

    protected void asyncHandshake()
    {
        String[] transports = this.transports.findTransportTypes(BAYEUX_VERSION);
        Transport newTransport = negotiateTransport(transports);
        transport = lifecycleTransport(transport, newTransport);
        logger.debug("Handshaking with transport {}", transport);

        Message.Mutable request = newMessage();
        request.setChannelId(Channel.MetaChannelId.HANDSHAKE.getChannelId());
        request.put(Message.VERSION_FIELD, BAYEUX_VERSION);
        request.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, transports);

        updateState(State.HANDSHAKING);
        send(request, true);
    }

    protected void syncHandshake() throws IOException
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    private void updateState(State newState)
    {
        logger.debug("State change: {} -> {}", state, newState);
        this.state = newState;
    }

    private Message.Mutable newMessage()
    {
        return transport.newMessage();
    }

    protected void send(Message.Mutable message, boolean meta)
    {
        boolean result = applyOutgoingExtensions(message, meta);
        if (result)
        {
            // TODO: handle batches
            transport.send(message);
        }
    }

    private boolean applyOutgoingExtensions(Message.Mutable message, boolean meta)
    {
        for (Extension extension : extensions)
        {
            try
            {
                boolean advance;
                if (meta)
                    advance = extension.sendMeta(this, message);
                else
                    advance = extension.send(this, message);

                if (!advance)
                {
                    logger.debug("Extension {} signalled to skip message {}", extension, message);
                    return false;
                }
            }
            catch (Exception x)
            {
                logger.debug("Exception while invoking extension " + extension, x);
            }
        }
        return true;
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

    private Transport negotiateTransport(String[] requestedTransports)
    {
        Transport transport = transports.negotiate(requestedTransports, BAYEUX_VERSION);
        if (transport == null)
            throw new TransportException("Could not negotiate transport: requested " +
                    Arrays.toString(requestedTransports) +
                    ", available " +
                    Arrays.toString(transports.findTransportTypes(BAYEUX_VERSION)));
        return transport;
    }

    public SessionChannel getSessionChannel(String channelName)
    {
        return null;
    }

    public Channel getChannel(String channelId)
    {
        return null;
    }

    public void startBatch()
    {
    }

    public void endBatch()
    {
    }

    public void batch(Runnable batch)
    {
        startBatch();
        try
        {
            batch.run();
        }
        finally
        {
            endBatch();
        }
    }

    public void disconnect()
    {
    }

    protected void receive(List<Message.Mutable> incomingMessages)
    {
        List<Message.Mutable> messages = applyIncomingExtensions(incomingMessages);

        for (Message message : messages)
        {
            Map<String, Object> advice = message.getAdvice();
            if (advice != null)
                this.advice = advice;

            String channelId = message.getChannelId();
            if (channelId == null)
            {
                logger.info("Ignoring invalid bayeux message, missing channel: {}", message);
                continue;
            }

            Boolean successfulField = (Boolean)message.get(Message.SUCCESSFUL_FIELD);
            boolean successful = successfulField != null && successfulField;
            Channel.MetaChannelId type = Channel.MetaChannelId.from(channelId);
            if (type == null)
            {
                if (successful)
                    processMessage(message);
                else
                    processUnsuccessful(message);
            }
            else if (type == Channel.MetaChannelId.HANDSHAKE)
            {
                if (state != State.HANDSHAKING)
                    throw new IllegalStateException();

                if (successful)
                    processHandshake(message);
                else
                    processUnsuccessful(message);
            }
            else if (type == Channel.MetaChannelId.CONNECT)
            {
                if (state != State.CONNECTED && state != State.DISCONNECTING)
                    // TODO: call a listener method ? Discard the message ?
                    throw new UnsupportedOperationException();

                if (successful)
                    processConnect(message);
                else
                    processUnsuccessful(message);
            }
            else if (type == Channel.MetaChannelId.DISCONNECT)
            {
                if (state != State.DISCONNECTING)
                    // TODO: call a listener method ? Discard the message ?
                    throw new UnsupportedOperationException();

                if (successful)
                    processDisconnect(message);
                else
                    processUnsuccessful(message);
            }
            else
            {
                throw new UnsupportedOperationException();
            }
        }
    }

    private List<Message.Mutable> applyIncomingExtensions(List<Message.Mutable> messages)
    {
        List<Message.Mutable> result = new ArrayList<Message.Mutable>();
        for (Message.Mutable message : messages)
        {
            for (Extension extension : extensions)
            {
                try
                {
                    boolean advance;
                    Channel.MetaChannelId metaChannel = Channel.MetaChannelId.from(message.getChannelId());
                    if (metaChannel != null)
                        advance = extension.rcvMeta(this, message);
                    else
                        advance = extension.rcv(this, message);

                    if (!advance)
                    {
                        logger.debug("Extension {} signalled to skip message {}", extension, message);
                        message = null;
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

    private void notifyMetaMessageListeners(Message message)
    {
        for (BayeuxListener listener : listeners)
        {
            if (listener instanceof MetaMessageListener)
            {
                ((MetaMessageListener)listener).onMetaMessage(this, message);
            }
        }
    }

    protected void processHandshake(Message handshake)
    {
        Boolean successfulField = (Boolean)handshake.get(Message.SUCCESSFUL_FIELD);
        boolean successful = successfulField != null && successfulField;

        if (successful)
        {
            // Renegotiate transport
            Transport newTransport = transports.negotiate((String[])handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD), BAYEUX_VERSION);
            if (newTransport == null)
            {
                // TODO: notify and stop
                throw new UnsupportedOperationException();
            }
            else if (newTransport != transport)
            {
                transport = lifecycleTransport(transport, newTransport);
            }

            updateState(State.CONNECTED);
            clientId = handshake.getClientId();

            notifyMetaMessageListeners(handshake);

            // TODO: internal batch ?

            followAdvice();
        }
        else
        {

        }
    }

    protected void processConnect(Message connect)
    {
//        metaChannels.notifySuscribers(getMutableMetaChannel(MetaChannelType.CONNECT), connect);
//        followAdvice();
    }

    protected void processMessage(Message message)
    {
//        channels.notifySubscribers(getMutableChannel(message.getChannelName()), message);
    }

    protected void processDisconnect(Message disconnect)
    {
//        metaChannels.notifySuscribers(getMutableMetaChannel(MetaChannelType.DISCONNECT), disconnect);
    }

    protected void processUnsuccessful(Message message)
    {
        // TODO
    }

    private void followAdvice()
    {
        Map<String, Object> advice = this.advice;
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
                    task = scheduler.schedule(new Runnable()
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
                throw new UnsupportedOperationException();
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
/*
        logger.debug("Connecting with transport {}", transport);
        MetaMessage.Mutable request = newMessage();
        request.setId(newMessageId());
        request.setClientId(clientId);
        request.setChannelName(MetaChannelType.CONNECT.getName());
        request.put(Message.CONNECTION_TYPE_FIELD, transport.getType());
        send(request, true);
*/
    }

    private String newMessageId()
    {
        return String.valueOf(messageIds.incrementAndGet());
    }

    private class Listener extends TransportListener.Adapter
    {
        @Override
        public void onMessages(List<Message.Mutable> messages)
        {
            receive(messages);
        }
    }

    private enum State
    {
        HANDSHAKING, CONNECTED, DISCONNECTING, DISCONNECTED
    }
}
