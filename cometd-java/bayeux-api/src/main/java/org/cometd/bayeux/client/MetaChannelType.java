package org.cometd.bayeux.client;

/**
 * @version $Revision$ $Date$
 */
public enum MetaChannelType
{
    HANDSHAKE("/meta/handshake"),
    CONNECT("/meta/connect"),
    SUBSCRIBE("/meta/subscribe"),
    UNSUBSCRIBE("/meta/unsubscribe"),
    PUBLISH("/meta/publish"),
    DISCONNECT("/meta/disconnect");

    private final String name;

    private MetaChannelType(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }
}
