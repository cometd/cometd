package org.cometd.bayeux;

/**
 * @version $Revision$ $Date$
 */
public class StandardMessage extends StandardStruct implements Message.Mutable
{
    public String getId()
    {
        return null;
    }

    public String getClientId()
    {
        return null;
    }

    public String getChannelName()
    {
        return null;
    }

    public Object getData()
    {
        return null;
    }

    public boolean isLazy()
    {
        return false;
    }

    public Struct.Mutable getExt(boolean create)
    {
        return null;
    }

    public Struct.Mutable getAdvice()
    {
        return null;
    }

    public void setLazy(boolean lazy)
    {
    }

    public Message getAssociated()
    {
        return null;
    }

    public void setAssociated(Message message)
    {
    }
}
