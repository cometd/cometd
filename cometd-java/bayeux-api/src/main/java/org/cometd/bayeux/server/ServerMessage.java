package org.cometd.bayeux.server;

import org.cometd.bayeux.Message;

/**
 * <p>Representation of a server side message.</p>
 *
 * @version $Revision: 1483 $ $Date: 2009-03-04 14:56:47 +0100 (Wed, 04 Mar 2009) $
 */
public interface ServerMessage extends Message
{
    /**
     * @return a message associated with this message on the server. Typically
     * this is a meta message that the current message is being sent in response
     * to.
     */
    ServerMessage.Mutable getAssociated();

    /**
     * @return true if the message is lazy and should not force the session's queue to be flushed
     */
    boolean isLazy();

    /**
     * The mutable version of a {@link ServerMessage}
     */
    public interface Mutable extends ServerMessage, Message.Mutable
    {
        /**
         * @param message the message associated with this message
         */
        void setAssociated(ServerMessage.Mutable message);

        /**
         * A lazy message does not provoke immediately delivery to the client
         * but it will be delivered at first occasion or after a timeout expires
         * @param lazy whether the message is lazy
         */
        void setLazy(boolean lazy);
    }
}
