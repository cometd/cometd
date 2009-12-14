package com.webtide.wharf.io;

import java.nio.channels.ServerSocketChannel;

/**
 * Responsibility: to create and operate on {@link ServerSocketChannel}s.
 *
 * @version $Revision$ $Date$
 */
public interface ServerConnector
{
    public void close();

    public int getPort();
}
