package org.cometd.bayeux.client.transport;

import org.cometd.bayeux.client.MetaMessage;

/**
 * @version $Revision$ $Date$
 */
public abstract class Exchange
{
    private final String uri;
    private final MetaMessage[] requests;

    protected Exchange(String uri, MetaMessage... requests)
    {
        this.uri = uri;
        this.requests = requests;
    }

    public String getURI()
    {
        return uri;
    }

    public MetaMessage[] getRequests()
    {
        return requests;
    }

    public abstract void failed(TransportException reason);
}
