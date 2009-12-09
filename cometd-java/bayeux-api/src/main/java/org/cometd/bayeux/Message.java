package org.cometd.bayeux;

import java.util.Map;

/**
 * @version $Revision$ $Date: 2009-12-08 09:42:45 +1100 (Tue, 08 Dec 2009) $
 */
public interface Message extends Cloneable
{
    String getClientId();
    String getId();
    String getChannelId();
    Object getData();
    Map<String, Object> getExt(boolean create);
    public Message getAssociated();
    public Object clone();
}
