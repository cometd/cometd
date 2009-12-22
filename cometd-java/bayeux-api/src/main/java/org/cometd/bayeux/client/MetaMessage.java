package org.cometd.bayeux.client;

import org.cometd.bayeux.Struct;

public interface MetaMessage extends Struct
{
    MetaChannel getMetaChannel();

    boolean isSuccessful();

    String getClientId();

    String getId();

    Struct getAdvice();

    interface Mutable extends MetaMessage, Struct.Mutable
    {
        Struct.Mutable getAdvice();

        void setMetaChannel(MetaChannel metaChannel);

        void setClientId(String clientId);

        void setSuccessful(boolean value);

        void setId(String id);

        void setAdvice(Struct.Mutable advice);
    }
}
