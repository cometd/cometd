package org.cometd.bayeux;

import java.util.EventListener;


/**
 * @version $Revision$ $Date: 2009-12-08 09:57:37 +1100 (Tue, 08 Dec 2009) $
 */
public interface MessageListener extends EventListener
{
    void onMessage(Message message);
}
