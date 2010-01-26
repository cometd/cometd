package org.cometd.client;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.cometd.bayeux.Transport;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.BayeuxClient.Extension;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.client.transport.TransportException;
import org.cometd.client.transport.TransportRegistry;


public class BayeuxClientImpl implements org.cometd.bayeux.client.BayeuxClient
{
    public static final String BAYEUX_VERSION = "1.0";
    
    private final List<Extension> _extensions = new CopyOnWriteArrayList<Extension>();

    protected final TransportRegistry _transports = new TransportRegistry();    
    protected final ScheduledExecutorService _scheduler;

    public BayeuxClientImpl()
    {
        this(Executors.newSingleThreadScheduledExecutor(), new LongPollingTransport());
    }

    public BayeuxClientImpl(ClientTransport... transports)
    {
        this(Executors.newSingleThreadScheduledExecutor(), transports);
    }

    public BayeuxClientImpl(ScheduledExecutorService scheduler, ClientTransport... transports)
    {
        this._scheduler = scheduler;
        for (ClientTransport transport : transports)
            this._transports.add(transport);
    }
    
    @Override
    public void addExtension(Extension extension)
    {
        _extensions.add(extension);
    }


    @Override
    public ClientSession newSession(String... servers)
    {
        return new ClientSessionImpl(this,servers);
    }

    @Override
    public List<String> getAllowedTransports()
    {
        return _transports.getAllowedTransports();
    }

    @Override
    public Set<String> getKnownTransportNames()
    {
        return _transports.getKnownTransports();
    }

    @Override
    public Transport getTransport(String transport)
    {
        // TODO Auto-generated method stub
        return null;
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
    public void setOption(String qualifiedName, Object value)
    {
        // TODO Auto-generated method stub
        
    }
}
