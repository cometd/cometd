package bayeux;

/**
 * @version $Revision$ $Date$
 */
public interface MetaChannel
{
    ChannelSubscription subscribe(MetaMessageListener listener);

    String getName();
}
