package org.cometd.client.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @version $Revision$ $Date$
 */
public class TransportRegistry
{
    private final Map<String,ClientTransport> _transports = new HashMap<String,ClientTransport>();
    private final List<String> _allowed = new ArrayList<String>();

    public void add(ClientTransport transport)
    {
        if (transport != null)
        {
            _transports.put(transport.getName(), transport);
            _allowed.add(transport.getName());
        }
    }

    public Set<String> getKnownTransports()
    {
        return Collections.unmodifiableSet(_transports.keySet());
    }

    public List<String> getAllowedTransports()
    {
        return Collections.unmodifiableList(_allowed);
    }

    public List<ClientTransport> negotiate(Object[] requestedTransports, String bayeuxVersion)
    {
        List<ClientTransport> list = new ArrayList<ClientTransport>();

        for (String transportName : _allowed)
        {
            for (Object requestedTransportName : requestedTransports)
            {
                if (requestedTransportName.equals(transportName))
                {
                    ClientTransport transport = getTransport(transportName);
                    if (transport.accept(bayeuxVersion))
                    {
                        list.add(transport);
                    }
                }
            }
        }
        return list;
    }

    public ClientTransport getTransport(String transport)
    {
        return _transports.get(transport);
    }
}
