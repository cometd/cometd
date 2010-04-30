package org.cometd.bayeux.server;

import org.cometd.bayeux.Message;


/* ------------------------------------------------------------ */
/** Representation of server side message.
 */
public interface ServerMessage extends Message
{
    /* ------------------------------------------------------------ */
    /**
     * @return A message associated with this message on the server. Typically 
     * this is a meta message that the current message is being sent in response
     * to. 
     */
    ServerMessage getAssociated();
    
    /* ------------------------------------------------------------ */
    /**
     * @return True if the message is lazy and should not force a sessions 
     * queue to be flushed.
     */
    boolean isLazy();

    /* ------------------------------------------------------------ */
    ServerMessage.Mutable asMutable();

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** Updateable ServerMessage
     */
    public interface Mutable extends ServerMessage,Message.Mutable
    {
        void setAssociated(ServerMessage message);
        void setLazy(boolean lazy);

        ServerMessage asImmutable();
    }
}
