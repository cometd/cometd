package org.cometd.bwtp.client.generator;

import java.nio.channels.ClosedChannelException;
import java.util.Map;

import org.cometd.bwtp.generator.BWTPGenerator;

/**
 * @version $Revision$ $Date$
 */
public interface ClientBWTPGenerator extends BWTPGenerator
{
    void upgradeRequest(String path, Map<String, String> headers) throws ClosedChannelException;
}
