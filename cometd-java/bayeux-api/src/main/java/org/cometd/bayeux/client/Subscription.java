package org.cometd.bayeux.client;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Client;


/**
 * @version $Revision$ $Date: 2009-12-08 09:42:45 +1100 (Tue, 08 Dec 2009) $
 */
public interface Subscription
{
    Client getClient();
    Channel getChannel();
    void unsubscribe();
}
