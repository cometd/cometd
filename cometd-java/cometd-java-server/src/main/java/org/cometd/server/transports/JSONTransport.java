package org.cometd.server.transports;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.ServerTransport;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;

public class JSONTransport extends LongPollingTransport
{
    public final static String NAME="long-polling";
    public final static String MIME_TYPE_OPTION="mimeType";
    
    protected String _mimeType="application/json;charset=UTF-8";
    
    
    public JSONTransport(BayeuxServerImpl bayeux,Map<String,Object> options)
    {
        super(bayeux,NAME,options);
        _prefix.add("json");
        setOption(MIME_TYPE_OPTION,_mimeType);
        _metaConnectDeliveryOnly=false;
    }
    
    @Override
    protected void init()
    {
        super.init();
        _mimeType=getOption(MIME_TYPE_OPTION,_mimeType);
    }

    @Override
    public boolean isMetaConnectDeliveryOnly()
    {
        return false;
    }

    @Override
    protected PrintWriter send(HttpServletRequest request,HttpServletResponse response,PrintWriter writer, ServerMessage message) throws IOException
    {
        if (writer==null)
        {
            response.setContentType(_mimeType);
            writer = response.getWriter();
            writer.append('['); 
        }
        else
            writer.append(','); 
        writer.append(message.getJSON());
        return writer;
    }

    @Override
    protected void complete(PrintWriter writer) throws IOException
    {
        writer.append("]\n");
        writer.close();
    }
    
}
