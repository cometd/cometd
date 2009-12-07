package bayeux;

/**
 * @version $Revision$ $Date$
 */
public interface Extension
{
    Message incoming(Message message);

    MetaMessage metaIncoming(MetaMessage metaMessage);

    Message outgoing(Message message);

    MetaMessage metaOutgoing(MetaMessage metaMessage);

    public static class Adapter implements Extension
    {
        public Message incoming(Message message)
        {
            return null;
        }

        public MetaMessage metaIncoming(MetaMessage metaMessage)
        {
            return null;
        }

        public Message outgoing(Message message)
        {
            return null;
        }

        public MetaMessage metaOutgoing(MetaMessage metaMessage)
        {
            return null;
        }
    }
}
