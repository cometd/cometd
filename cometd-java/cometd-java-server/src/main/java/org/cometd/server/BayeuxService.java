package org.cometd.server;

import org.cometd.bayeux.server.BayeuxServer;


/* ------------------------------------------------------------ */
/**
 * @deprecated Use {@link AbstractService}
 */
@Deprecated
public abstract class BayeuxService extends AbstractService
{
    public BayeuxService(BayeuxServer bayeux, String name)
    {
        super(bayeux,name);
    }

    @Deprecated
    protected void subscribe(String channelId, String methodName)
    {
        super.addService(channelId, methodName);
    }
}
