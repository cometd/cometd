package com.webtide.wharf.io.async;

/**
 * @version $Revision$ $Date$
 */
public interface AsyncConnectorListener
{
    AsyncInterpreter connected(AsyncCoordinator coordinator);
}
