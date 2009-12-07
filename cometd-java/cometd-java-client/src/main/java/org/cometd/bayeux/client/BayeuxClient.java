package org.cometd.bayeux.client;

import java.util.Arrays;

import bayeux.Channel;
import bayeux.Extension;
import bayeux.ExtensionRegistration;
import bayeux.MetaChannel;
import bayeux.MetaChannelType;
import bayeux.MetaMessage;
import bayeux.client.Client;
import bayeux.client.Session;
import org.cometd.bayeux.BayeuxMetaMessage;
import org.cometd.bayeux.MetaChannels;
import org.cometd.bayeux.client.transport.Exchange;
import org.cometd.bayeux.client.transport.Transport;
import org.cometd.bayeux.client.transport.TransportException;
import org.cometd.bayeux.client.transport.TransportRegistry;

/**
 * @version $Revision$ $Date$
 */
public class BayeuxClient implements Client, Session
{
    private static final String BAYEUX_VERSION = "1.0";

    private final TransportRegistry transports = new TransportRegistry();
    private final String uri;

    public BayeuxClient(String uri, Transport transport, Transport... transports)
    {
        this.uri = uri;
        this.transports.add(transport);
        for (Transport t : transports)
            this.transports.add(t);
    }

    public ExtensionRegistration registerExtension(Extension extension)
    {
        return null;
    }

    public MetaChannel metaChannel(MetaChannelType type)
    {
        return MetaChannels.from(type);
    }

    public Session handshake()
    {
        String[] transports = this.transports.findTransportTypes(BAYEUX_VERSION);
        Transport transport = negotiateTransport(transports);

        final BayeuxMetaMessage request = new BayeuxMetaMessage(metaChannel(MetaChannelType.HANDSHAKE));
        request.put("version", BAYEUX_VERSION);
        request.put("supportedConnectionTypes", transports);
        // TODO: call extensions

        Exchange exchange = new Exchange(uri, request)
        {
            public void success(MetaMessage[] responses)
            {
                handshakeResponse(request, responses);
            }

            public void failure(TransportException reason)
            {
                handshakeFailure(request, reason);
            }
        };

        transport.send(exchange, true);

        // TODO: throw if failed
        return this;
    }

    protected void handshakeResponse(MetaMessage request, MetaMessage[] response)
    {
        // TODO: call extensions
    }

    protected void handshakeFailure(MetaMessage request, TransportException reason)
    {

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

    public Channel channel(String channelName)
    {
        return null;
    }

    public void batch(Runnable batch)
    {
    }

    public void disconnect()
    {
    }
}
