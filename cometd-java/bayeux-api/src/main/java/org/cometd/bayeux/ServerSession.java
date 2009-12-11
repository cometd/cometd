package org.cometd.bayeux;


/**
 * @version $Revision$ $Date: 2009-12-08 09:42:45 +1100 (Tue, 08 Dec 2009) $
 */
public interface ServerSession extends Client
{
    void deliver(Message msg);
    void deliverLazy(Message msg);
    void addMessageListener(MessageListener listener);
    void disconnect();
    
}
