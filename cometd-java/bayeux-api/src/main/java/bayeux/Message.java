package bayeux;

import java.util.Map;

/**
 * @version $Revision$ $Date$
 */
public interface Message
{
    String getClientId();
    String getId();
    Channel getChannel();
    Object getData();
    Map<String, Object> getExt(boolean create);
}
