package bayeux;

/**
 * @version $Revision$ $Date$
 */
public interface Channel
{
    ChannelSubscription subscribe(MessageListener listener);

    void publish(Object data);
}
