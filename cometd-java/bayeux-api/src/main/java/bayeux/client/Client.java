package bayeux.client;

import bayeux.Extension;
import bayeux.ExtensionRegistration;
import bayeux.MetaChannel;
import bayeux.MetaChannelType;

/**
 * @version $Revision$ $Date$
 */
public interface Client
{
    ExtensionRegistration registerExtension(Extension extension);

    MetaChannel metaChannel(MetaChannelType type);

    Session handshake();
}
