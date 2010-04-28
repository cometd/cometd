package org.cometd.server.filter;

import java.util.Arrays;
import java.util.List;

import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.server.BayeuxServerImpl;

public class DataFilterMessageListener implements ServerChannel.MessageListener
{
    BayeuxServerImpl _bayeux;
    List<DataFilter> _filters;
    
    public DataFilterMessageListener(DataFilter... filters)
    {
        _filters=Arrays.asList(filters);
    }
    
    @Override
    public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
    {
        Object data = message.getData();
        final Object orig = data;
        for (DataFilter filter : _filters)
        {
            data=filter.filter(from,channel,data);
            if (data==null)
                return false;
        }
        if (data!=orig)
            message.setData(data);
        return true;
    }

}
