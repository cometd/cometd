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
    Message getAssociated();
    
    /* ------------------------------------------------------------ */
    /**
     * @return True if the message is lazy and should not force a sessions 
     * queue to be flushed.
     */
    boolean isLazy();
    
    /* ------------------------------------------------------------ */
    /** Increment reference count for the messsage.
     * If a reference to a message is to be kept longer than the calling scope 
     * into which a message was passed, then the holder of the reference should
     * call incRef() to avoid the message being cleared and pooled.
     */
    void incRef();
    
    /* ------------------------------------------------------------ */
    /** Decrement reference for the messsage.
     * If a reference to a message is to be kept longer than the calling scope 
     * into which a message was passed, then the holder of the reference should
     * call decRef() once thar reference is cleared, so the message can be cleared 
     * and pooled.
     */
    void decRef();


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** Updateable ServerMessage
     */
    public interface Mutable extends ServerMessage,Message.Mutable
    {
        void setAssociated(Message message);
        void setLazy(boolean lazy);
        
    }
}
