package org.cometd.bayeux.client;


public interface MetaMessageListener extends MetaChannel.Listener
{
    void onMetaMessage(MetaMessage message);
    
    interface Successful extends MetaChannel.Listener
    {
        void onSuccessful(MetaMessage message);
    }
    
    interface Unsuccessful extends MetaChannel.Listener
    {
        void onUnsuccessful(MetaMessage message);
    }
}
