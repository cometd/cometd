package bayeux;

import java.util.Map;

/**
 * @version $Revision$ $Date$
 */
public interface MetaMessage
{
    String getId();

    String getClientId();

    MetaChannel getMetaChannel();

    Map<String, Object> getExt(boolean create);
}
