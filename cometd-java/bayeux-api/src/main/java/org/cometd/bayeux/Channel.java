package org.cometd.bayeux;

public interface Channel
{
    public enum MetaType
    {
        HANDSHAKE("/meta/handshake"),
        CONNECT("/meta/connect"),
        SUBSCRIBE("/meta/subscribe"),
        UNSUBSCRIBE("/meta/unsubscribe"),
        DISCONNECT("/meta/disconnect");

        private final String _channelId;

        private MetaType(String channelId)
        {
            _channelId = channelId;
        }

        public String getChannelId()
        {
            return _channelId;
        }
    }
    
    
    String getChannelId();
    boolean isMetaChannel();
    boolean isServiceChannel();
}
