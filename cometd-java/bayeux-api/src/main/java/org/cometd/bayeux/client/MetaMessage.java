package org.cometd.bayeux.client;

public interface MetaMessage //extends Message
{
    MetaChannel getMetaChannel();

    Object get(String field);

    boolean isSuccessful();

    String getClientId();

    String getId();

    interface Mutable extends MetaMessage
    {
        void setMetaChannel(MetaChannel metaChannel);

        void put(String field, Object value);

        void setAssociated(MetaMessage associated);

        void setClientId(String clientId);

        void setSuccessful(boolean value);

        void setId(String id);
    }
}
