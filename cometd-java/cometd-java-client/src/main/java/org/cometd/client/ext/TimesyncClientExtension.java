package org.cometd.client.ext;

import java.util.Map;

import org.cometd.bayeux.Message.Mutable;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSession.Extension;
import org.eclipse.jetty.util.ajax.JSON;

public class TimesyncClientExtension implements Extension
{
    private volatile int _lag;
    private volatile int _offset;

    public boolean rcv(ClientSession session, Mutable message)
    {
        return true;
    }

    public boolean rcvMeta(ClientSession session, Mutable message)
    {
        Map<String,Object> ext=message.getExt(false);
        if (ext!=null)
        {
            Map<String,Object> sync=(Map<String,Object>)ext.get("timesync");
            if (sync!=null)
            {
                long now = System.currentTimeMillis();

                final long tc=((Number)sync.get("tc")).longValue();
                final long ts=((Number)sync.get("ts")).longValue();
                final int p=((Number)sync.get("p")).intValue();
                // final int a=((Number)sync.get("a")).intValue();

                int l2 = (int) ((now - tc - p) / 2);
                int o2 = (int) (ts-tc-l2);

                _lag=_lag==0?l2:(_lag+l2)/2;
                _offset=_offset==0?o2:(_offset+o2)/2;
            }
        }

        return true;
    }

    public boolean send(ClientSession session, Mutable message)
    {
        return true;
    }

    public boolean sendMeta(ClientSession session, Mutable message)
    {
        Map<String,Object> ext=message.getExt(true);
        long now = System.currentTimeMillis();
        JSON.Literal timesync=new JSON.Literal("{\"tc\":"+now+",\"l\":"+_lag+",\"o\":"+_offset+"}");
        ext.put("timesync",timesync);
        return true;
    }

    public int getOffset()
    {
        return _offset;
    }

    public int getLag()
    {
        return _lag;
    }

    public long getServerTime()
    {
        return System.currentTimeMillis()+_offset;
    }
}
