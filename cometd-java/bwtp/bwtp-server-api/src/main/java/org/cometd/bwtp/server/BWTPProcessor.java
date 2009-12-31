package org.cometd.bwtp.server;

import org.cometd.bwtp.BWTPConnection;
import org.cometd.bwtp.BWTPListener;

/**
 * @version $Revision$ $Date$
 */
public interface BWTPProcessor extends BWTPListener
{
    void onConnect(BWTPConnection connection);

    public static class Adapter extends BWTPListener.Adapter implements BWTPProcessor
    {
        public void onConnect(BWTPConnection connection)
        {
        }
    }
}
