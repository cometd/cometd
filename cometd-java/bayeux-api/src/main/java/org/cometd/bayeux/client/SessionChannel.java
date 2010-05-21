package org.cometd.bayeux.client;

import org.cometd.bayeux.Message;

/**
 * @deprecated Use {@link ClientSessionChannel} instead
 */
@Deprecated
public interface SessionChannel extends ClientSessionChannel
{
    /**
     * @deprecated Use {@link MessageListener} instead
     */
    @Deprecated
    public abstract class SubscriberListener implements MessageListener
    {
        @Override
        public final void onMessage(ClientSessionChannel channel, Message message)
        {
            onMessage((SessionChannel)channel, message);
        }

        public abstract void onMessage(SessionChannel channel, Message message);
    }

    /**
     * @deprecated Use {@link MessageListener} instead
     */
    public abstract class MetaChannelListener implements MessageListener
    {
        public final void onMessage(ClientSessionChannel channel, Message message)
        {
            if (message.isMeta())
            {
                Object error = message.get(Message.ERROR_FIELD);
                onMetaMessage((SessionChannel)channel, message, message.isSuccessful(), error == null ? null : String.valueOf(error));
            }
        }

        public abstract void onMetaMessage(SessionChannel channel, Message message, boolean successful, String error);
    }
}
