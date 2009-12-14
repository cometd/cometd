package org.cometd.bayeux.client;


public interface MetaMessageListener extends MetaChannel.Listener
{
    void onMetaMessage(MetaMessage message);
    
    interface Successful
    {
        void onSuccessful(MetaMessage message);
    }
    
    interface Unsuccessful
    {
        void onUnsuccessful(MetaMessage message);
    }
}
