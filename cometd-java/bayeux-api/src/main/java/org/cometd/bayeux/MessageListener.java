package org.cometd.bayeux;

import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.server.ServerSession;


/**
 * @version $Revision$ $Date: 2009-12-08 09:57:37 +1100 (Tue, 08 Dec 2009) $
 */
public interface MessageListener extends ServerSession.Listener,ClientSession.Listener
{
    void onMessage(Message message);
}
