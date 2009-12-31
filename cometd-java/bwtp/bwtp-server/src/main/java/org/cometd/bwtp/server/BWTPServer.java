package org.cometd.bwtp.server;

import org.cometd.wharf.async.AsyncConnectorListener;
import org.cometd.wharf.async.AsyncCoordinator;
import org.cometd.wharf.async.AsyncInterpreter;

/**
 * @version $Revision$ $Date$
 */
public class BWTPServer implements AsyncConnectorListener
{
    private final BWTPProcessor processor;

    public BWTPServer()
    {
        this(new BWTPProcessor.Adapter());
    }

    public BWTPServer(BWTPProcessor processor)
    {
        this.processor = processor;
    }

    public AsyncInterpreter connected(AsyncCoordinator coordinator)
    {
        return new ServerBWTPAsyncInterpreter(coordinator, processor);
    }
}
