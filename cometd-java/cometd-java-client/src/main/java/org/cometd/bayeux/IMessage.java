package org.cometd.bayeux;

/**
 * @version $Revision$ $Date$
 */
public interface IMessage extends MetaMessage, Message
{
    interface Mutable extends MetaMessage.Mutable, Message.Mutable
    {
    }
}
