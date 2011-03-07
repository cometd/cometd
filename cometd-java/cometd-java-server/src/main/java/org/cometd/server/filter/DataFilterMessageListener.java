package org.cometd.server.filter;

import java.util.Arrays;
import java.util.List;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;

/**
 * 
 * a MessageListener that applies DataFilters to the received messages.
 *
 */
public class DataFilterMessageListener implements ServerChannel.MessageListener
{
    final BayeuxServerImpl _bayeux;
    final List<DataFilter> _filters;

    public DataFilterMessageListener(DataFilter... filters)
    {
        _bayeux=null;
        _filters=Arrays.asList(filters);
    }
    
    public DataFilterMessageListener(BayeuxServer bayeux, DataFilter... filters)
    {
        _bayeux=(BayeuxServerImpl)bayeux;
        _filters=Arrays.asList(filters);
    }

    public boolean onMessage(ServerSession from, ServerChannel channel, Mutable message)
    {
        try
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
        catch(DataFilter.Abort a)
        {
            if (_bayeux!=null)
                _bayeux.getLogger().debug(a);
        }
        catch(Exception e)
        {
            if (_bayeux!=null)
                _bayeux.getLogger().warn(e);
        }
        return false;
    }

}
