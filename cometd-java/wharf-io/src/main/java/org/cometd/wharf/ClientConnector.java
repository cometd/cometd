package org.cometd.wharf;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

/**
 * Responsibility: to create and operate on {@link SocketChannel}s.
 * @version $Revision$ $Date$
 */
public interface ClientConnector
{
    public void connect(InetSocketAddress address) throws ConnectException;

    public void close();
}
