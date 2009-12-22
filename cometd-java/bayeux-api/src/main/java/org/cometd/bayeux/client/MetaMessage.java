package org.cometd.bayeux.client;

import java.util.Map;

public interface MetaMessage //extends Message
{
    MetaChannel getMetaChannel();

    Object get(String field);

    boolean isSuccessful();

    String getClientId();

    String getId();

    Map<String, Object> getAdvice();

    interface Mutable extends MetaMessage
    {
        void setMetaChannel(MetaChannel metaChannel);

        void put(String field, Object value);

        void setAssociated(MetaMessage associated);

        void setClientId(String clientId);

        void setSuccessful(boolean value);

        void setId(String id);

        void setAdvice(Map<String, Object> advice);
    }
}
