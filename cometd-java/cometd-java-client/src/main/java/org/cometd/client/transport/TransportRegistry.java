package org.cometd.client.transport;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

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
            _transports.put(transport.getName(),transport);
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
    
    public ClientTransport negotiate(String[] requestedTransports, String bayeuxVersion)
    {
        for (String transport : _allowed)
        {
            for (String requestedTransport : requestedTransports)
            {
                if (requestedTransport.equals(transport))
                {
                    if (_transports.get(transport).accept(bayeuxVersion))
                    {
                        return _transports.get(transport);
                    }
                }
            }
        }
        return null;
    }

    public String[] findTransportTypes(String bayeuxVersion)
    {
        List<String> result = new ArrayList<String>();
        for (String name : _allowed)
        {
            ClientTransport transport=_transports.get(name);
            if (transport.accept(bayeuxVersion))
            {
                result.add(transport.getName());
            }
        }
        return result.toArray(new String[result.size()]);
    }
}
