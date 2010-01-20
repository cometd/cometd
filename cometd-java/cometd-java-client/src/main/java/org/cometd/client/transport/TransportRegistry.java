package org.cometd.client.transport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

/**
 * @version $Revision$ $Date$
 */
public class TransportRegistry
{
    private final Set<ClientTransport> transports = new LinkedHashSet<ClientTransport>();

    public void add(ClientTransport transport)
    {
        if (transport != null)
            transports.add(transport);
    }

    public ClientTransport negotiate(String[] requestedTransports, String bayeuxVersion)
    {
        for (ClientTransport transport : transports)
        {
            for (String requestedTransport : requestedTransports)
            {
                if (requestedTransport.equals(transport.getName()))
                {
                    if (transport.accept(bayeuxVersion))
                    {
                        return transport;
                    }
                }
            }
        }
        return null;
    }

    public String[] findTransportTypes(String bayeuxVersion)
    {
        List<String> result = new ArrayList<String>();
        for (ClientTransport transport : transports)
        {
            if (transport.accept(bayeuxVersion))
            {
                result.add(transport.getName());
            }
        }
        return result.toArray(new String[result.size()]);
    }
}
