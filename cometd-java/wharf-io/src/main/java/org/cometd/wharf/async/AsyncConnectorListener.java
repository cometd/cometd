package org.cometd.wharf.async;

/**
 * @version $Revision$ $Date$
 */
public interface AsyncConnectorListener
{
    AsyncInterpreter connected(AsyncCoordinator coordinator);
}
