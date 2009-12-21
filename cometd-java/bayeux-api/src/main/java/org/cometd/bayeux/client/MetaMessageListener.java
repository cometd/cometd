package org.cometd.bayeux.client;


public interface MetaMessageListener //extends MetaChannel.Listener
{
    void onMetaMessage(MetaMessage message);

/*
    // TODO: these are useful, but must be easy to use
    interface Successful extends MetaChannel.Listener
    {
        void onSuccessful(MetaMessage message);
    }

    interface Unsuccessful extends MetaChannel.Listener
    {
        void onUnsuccessful(MetaMessage message);
    }
*/
}
