package org.cometd.bayeux;

/**
 * @version $Revision$ $Date$
 */
public interface CommonMessage extends Struct
{
    String getId();

    String getClientId();

    String getChannelName();

    Struct getAdvice();

    Struct getExt(boolean create);

    interface Mutable extends CommonMessage, Struct.Mutable
    {
        void setId(String id);

        void setClientId(String clientId);

        void setChannelName(String channelName);

        void setAdvice(Struct.Mutable advice);

        void setExt(Struct.Mutable ext);
    }
}
