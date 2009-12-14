package com.webtide.wharf.io;

import java.nio.channels.SocketChannel;

/**
 * Responsibility: to create and operate on {@link SocketChannel}s.
 * @version $Revision$ $Date$
 */
public interface ClientConnector
{
    public void close();
}
