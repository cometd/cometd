package org.cometd.bayeux.client;

import org.cometd.bayeux.Message;

public interface MetaMessage extends Message
{
    boolean isSuccessful();
    String getError();
}
