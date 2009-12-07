package org.cometd.bayeux.client.transport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

/**
 * @version $Revision$ $Date$
 */
public class TransportRegistry
{
    private final Set<Transport> transports = new LinkedHashSet<Transport>();

    public void add(Transport transport)
    {
        if (transport != null)
            transports.add(transport);
    }

    public Transport negotiate(String[] requestedTransports, String bayeuxVersion)
    {
        for (Transport transport : transports)
        {
            for (String requestedTransport : requestedTransports)
            {
                if (requestedTransport.equals(transport.getType()))
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
        for (Transport transport : transports)
        {
            if (transport.accept(bayeuxVersion))
            {
                result.add(transport.getType());
            }
        }
        return result.toArray(new String[result.size()]);
    }
}
