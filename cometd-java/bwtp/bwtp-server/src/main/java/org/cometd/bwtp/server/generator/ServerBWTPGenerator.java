package org.cometd.bwtp.server.generator;

import java.nio.channels.ClosedChannelException;
import java.util.Map;

import org.cometd.bwtp.BWTPVersionType;
import org.cometd.bwtp.generator.BWTPGenerator;

/**
 * @version $Revision$ $Date$
 */
public interface ServerBWTPGenerator extends BWTPGenerator
{
    void upgradeResponse(BWTPVersionType version, Map<String, String> headers) throws ClosedChannelException;
}
