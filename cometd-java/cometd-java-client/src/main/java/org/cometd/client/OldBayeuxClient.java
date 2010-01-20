package org.cometd.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.BayeuxClient;
import org.cometd.bayeux.client.ClientChannel;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.SessionChannel;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.TransportException;
import org.cometd.client.transport.TransportListener;
import org.cometd.client.transport.TransportRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @version $Revision$ $Date$
 */
public class OldBayeuxClient implements BayeuxClient
{
    private static final String BAYEUX_VERSION = "1.0";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();
    private final TransportRegistry _transports = new TransportRegistry();
    private final TransportListener _transportListener = new Listener();
    private final AtomicInteger _messageIds = new AtomicInteger();
    private final ScheduledExecutorService _scheduler;
    private volatile State _state = State.DISCONNECTED;
    private volatile ClientTransport _transport;
    private volatile String _clientId;
    private volatile Map<String,Object> _advice;
    private volatile ScheduledFuture<?> _task;

    public OldBayeuxClient(ClientTransport... transports)
    {
        this(Executors.newSingleThreadScheduledExecutor(), transports);
    }

    public OldBayeuxClient(ScheduledExecutorService scheduler, ClientTransport... transports)
    {
        this._scheduler = scheduler;
        for (ClientTransport transport : transports)
            this._transports.add(transport);
    }

    public String getId()
    {
        return _clientId;
    }

    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }

    public void removeExtension(Extension extension)
    {
        _extensions.remove(extension);
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
        String[] transports = this._transports.findTransportTypes(BAYEUX_VERSION);
        ClientTransport newTransport = negotiateTransport(transports);
        _transport = lifecycleTransport(_transport, newTransport);
        logger.debug("Handshaking with transport {}", _transport);

        Message.Mutable request = newMessage();
        request.setChannelId(Channel.META_HANDSHAKE);
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
        logger.debug("State change: {} -> {}", _state, newState);
        this._state = newState;
    }

    private Message.Mutable newMessage()
    {
        return _transport.newMessage();
    }

    protected void send(Message.Mutable message)
    {
        boolean result = applyOutgoingExtensions(message);
        if (result)
        {
            // TODO: handle batches
            _transport.send(message);
        }
    }

    private boolean applyOutgoingExtensions(Message.Mutable message)
    {
        for (Extension extension : _extensions)
        {
            try
            {
                boolean advance;
                if (message.isMeta())
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

    private ClientTransport lifecycleTransport(ClientTransport oldTransport, ClientTransport newTransport)
    {
        if (oldTransport != null)
        {
            oldTransport.removeListener(_transportListener);
            oldTransport.destroy();
        }
        newTransport.addListener(_transportListener);
        newTransport.init();
        return newTransport;
    }

    private ClientTransport negotiateTransport(String[] requestedTransports)
    {
        ClientTransport transport = _transports.negotiate(requestedTransports, BAYEUX_VERSION);
        if (transport == null)
            throw new TransportException("Could not negotiate transport: requested " +
                    Arrays.toString(requestedTransports) +
                    ", available " +
                    Arrays.toString(_transports.findTransportTypes(BAYEUX_VERSION)));
        return transport;
    }

    public SessionChannel getSessionChannel(String channelName)
    {
        return null;
    }

    public ClientChannel getChannel(String channelId)
    {
        return null;
    }


    protected void receive(List<Message.Mutable> incomingMessages)
    {
        List<Message.Mutable> messages = applyIncomingExtensions(incomingMessages);

        for (Message message : messages)
        {
            Map<String, Object> advice = message.getAdvice();
            if (advice != null)
                this._advice = advice;

            String channelId = message.getChannelId();
            if (channelId == null)
            {
                logger.info("Ignoring invalid bayeux message, missing channel: {}", message);
                continue;
            }

            Boolean successfulField = (Boolean)message.get(Message.SUCCESSFUL_FIELD);
            boolean successful = successfulField != null && successfulField;
            
            if (Channel.META_HANDSHAKE.equals(channelId))
            {
                if (_state != State.HANDSHAKING)
                    throw new IllegalStateException();

                if (successful)
                    processHandshake(message);
                else
                    processUnsuccessful(message);
            }
            else if (Channel.META_CONNECT.equals(channelId))
            {
                if (_state != State.CONNECTED && _state != State.DISCONNECTING)
                    // TODO: call a listener method ? Discard the message ?
                    throw new UnsupportedOperationException();

                if (successful)
                    processConnect(message);
                else
                    processUnsuccessful(message);
            }
            else if (Channel.META_DISCONNECT.equals(channelId))
            {
                if (_state != State.DISCONNECTING)
                    // TODO: call a listener method ? Discard the message ?
                    throw new UnsupportedOperationException();

                if (successful)
                    processDisconnect(message);
                else
                    processUnsuccessful(message);
            }
            else
            {
                if (successful)
                    processMessage(message);
                else
                    processUnsuccessful(message);
            }
        }
    }

    private List<Message.Mutable> applyIncomingExtensions(List<Message.Mutable> messages)
    {
        List<Message.Mutable> result = new ArrayList<Message.Mutable>();
        for (Message.Mutable message : messages)
        {
            for (Extension extension : _extensions)
            {
                try
                {
                    boolean advance;

                    if (message.isMeta())
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

    protected void processHandshake(Message handshake)
    {
        Boolean successfulField = (Boolean)handshake.get(Message.SUCCESSFUL_FIELD);
        boolean successful = successfulField != null && successfulField;

        if (successful)
        {
            // Renegotiate transport
            ClientTransport newTransport = _transports.negotiate((String[])handshake.get(Message.SUPPORTED_CONNECTION_TYPES_FIELD), BAYEUX_VERSION);
            if (newTransport == null)
            {
                // TODO: notify and stop
                throw new UnsupportedOperationException();
            }
            else if (newTransport != _transport)
            {
                _transport = lifecycleTransport(_transport, newTransport);
            }

            updateState(State.CONNECTED);
            _clientId = handshake.getClientId();

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
        Map<String, Object> advice = this._advice;
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
                    _task = _scheduler.schedule(new Runnable()
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
        return String.valueOf(_messageIds.incrementAndGet());
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

    public boolean isConnected()
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public ClientSession newSession(String... servers)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<String> getAllowedTransports()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Set<String> getKnownTransportNames()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Object getOption(String qualifiedName)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Set<String> getOptionNames()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public org.cometd.bayeux.Transport getTransport(String transport)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setOption(String qualifiedName, Object value)
    {
        // TODO Auto-generated method stub
        
    }
}
