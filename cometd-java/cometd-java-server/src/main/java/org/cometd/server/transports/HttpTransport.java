package org.cometd.server.transports;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerTransport;


public abstract class HttpTransport extends ServerTransport
{
    public static final String MESSAGE_PARAM="message";
    
    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<HttpServletRequest>();
    
    protected HttpTransport(BayeuxServerImpl bayeux,String name,Map<String,Object> options)
    {
        super(bayeux,name,options);
    }
    
    @Override
    protected void init()
    {
        super.init();
    }

    public abstract void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;
    
    
    protected ServerMessage.Mutable[] parseMessages(HttpServletRequest request)
        throws IOException
    {
        String content_type=request.getContentType();

        // Get message batches either as JSON body or as message parameters
        if (content_type!=null && !content_type.startsWith("application/x-www-form-urlencoded"))
            return _bayeux.getServerMessagePool().parse(request.getReader());
        
        String[] batches=request.getParameterValues(MESSAGE_PARAM);
        
        if (batches == null || batches.length == 0)
            return null;

        if (batches.length == 1)
            return _bayeux.getServerMessagePool().parse(batches[0]);

        List<ServerMessage.Mutable> messages=new ArrayList<ServerMessage.Mutable>();
        for (int i=0; i < batches.length; i++)
        {
            if (batches[i] == null)
                continue;
            messages.addAll(Arrays.asList(_bayeux.getServerMessagePool().parse(batches[i])));
        }
        return messages.toArray(new ServerMessage.Mutable[messages.size()]);
    }
    
    public void setCurrentRequest(HttpServletRequest request)
    {
        _currentRequest.set(request);
    }
    
    public HttpServletRequest getCurrentRequest()
    {
        return _currentRequest.get();
    }
    
}
