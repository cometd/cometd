package org.cometd.server.filter;

import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerMessage.Mutable;

public interface DataFilter
{
    /**
     * @param from the {@link Client} that sends the data
     * @param channel the channel the data is being sent to
     * @param data the data being sent
     * @return the transformed data or null if the message should be aborted
     */
    public abstract Object filter(ServerSession from, ServerChannel channel, Object data);


    /**
     * Abort the message by throwing this exception
     *
     */
    public class Abort extends RuntimeException
    {
        public Abort()
        {
            super();
        }
        public Abort(String msg)
        {
            super(msg);
        }
        public Abort(String msg,Throwable cause)
        {
            super(msg,cause);
        }

    }
}
