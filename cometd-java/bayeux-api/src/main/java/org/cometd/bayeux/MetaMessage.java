package org.cometd.bayeux;

public interface MetaMessage extends CommonMessage
{
    boolean isSuccessful();

    interface Mutable extends CommonMessage.Mutable, MetaMessage
    {
        void setSuccessful(boolean value);
    }
}
