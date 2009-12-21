package org.cometd.bayeux.client;

import java.util.Arrays;

import org.cometd.bayeux.BayeuxException;
import org.cometd.bayeux.Extension;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.transport.Transport;
import org.cometd.bayeux.client.transport.TransportException;
import org.cometd.bayeux.client.transport.TransportListener;
import org.cometd.bayeux.client.transport.TransportRegistry;

/**
 * @version $Revision$ $Date$
 */
public class StandardClientBayeux implements ClientBayeux
{
    private static final String BAYEUX_VERSION = "1.0";

    private final MetaChannelRegistry metaChannels = new MetaChannelRegistry();
    private final ChannelRegistry channels = new ChannelRegistry();
    private final TransportRegistry transports = new TransportRegistry();
    private final TransportListener transportListener = new Listener();
    private volatile State state = State.DISCONNECTED;
    private volatile Transport transport;
    private volatile String clientId;

    public StandardClientBayeux(Transport... transports)
    {
        for (Transport t : transports)
            this.transports.add(t);
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

        MetaMessage.Mutable request = transport.newMetaMessage(null);
        request.setMetaChannel(getMetaChannel(MetaChannelType.HANDSHAKE));
        request.put(Message.VERSION_FIELD, BAYEUX_VERSION);
        request.put(Message.SUPPORTED_CONNECTION_TYPES_FIELD, transports);

        this.state = State.HANDSHAKING;
        send(request);
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
    }

    public void removeExtension(Extension extension)
    {
    }

    public Channel getChannel(String channelName)
    {
        return channels.from(channelName, true);
    }

    public void batch(Runnable batch)
    {

    }

    public void disconnect()
    {
        if (isDisconnected())
            throw new IllegalStateException();

        MetaMessage.Mutable metaMessage = transport.newMetaMessage(null);
        metaMessage.setMetaChannel(getMetaChannel(MetaChannelType.DISCONNECT));

        state = State.DISCONNECTING;
        send(metaMessage);
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

    protected void send(MetaMessage... metaMessages)
    {
        // TODO: call extensions

        transport.send(metaMessages);
    }

    protected void receive(MetaMessage metaMessage)
    {
        // TODO: call extensions

        switch (state)
        {
            case HANDSHAKING:
            {
                if (metaMessage.getMetaChannel().getType() != MetaChannelType.HANDSHAKE)
                    // TODO: call a listener method ? Discard the message ?
                    throw new BayeuxException();

                if (metaMessage.isSuccessful())
                    processHandshake(metaMessage);
                else
                    processUnsuccessful(metaMessage);

                break;
            }
            case DISCONNECTING:
            {
                // TODO
                break;
            }
            default:
                throw new BayeuxException();
        }
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

        state = State.CONNECTED;
        clientId = handshake.getClientId();

        metaChannels.notifySuscribers(getMutableMetaChannel(MetaChannelType.HANDSHAKE), handshake);

        // TODO: internal batch ?

        // TODO: handle advice
    }

    protected void processUnsuccessful(MetaMessage metaMessage)
    {
        // TODO
    }

    private class Listener extends TransportListener.Adapter
    {
        @Override
        public void onMetaMessages(MetaMessage... metaMessages)
        {
            for (MetaMessage metaMessage : metaMessages)
                receive(metaMessage);
        }
    }

    private boolean isDisconnected()
    {
        return state == State.DISCONNECTED;
    }

    private enum State
    {
        HANDSHAKING, CONNECTED, DISCONNECTING, DISCONNECTED
    }
}
