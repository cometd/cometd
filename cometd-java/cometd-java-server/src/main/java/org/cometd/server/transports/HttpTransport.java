package org.cometd.server.transports;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerMessageImpl;
import org.cometd.server.AbstractServerTransport;


public abstract class HttpTransport extends AbstractServerTransport
{
    public static final String MESSAGE_PARAM="message";
    
    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<HttpServletRequest>();
    
    protected HttpTransport(BayeuxServerImpl bayeux,String name)
    {
        super(bayeux,name);
    }
    
    @Override
    protected void init()
    {
        super.init();
    }

    public abstract boolean accept(HttpServletRequest request);
    
    public abstract void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;
    
    
    protected ServerMessage.Mutable[] parseMessages(HttpServletRequest request)
        throws IOException
    {
        String content_type=request.getContentType();

        // Get message batches either as JSON body or as message parameters
        if (content_type!=null && !content_type.startsWith("application/x-www-form-urlencoded"))
            return ServerMessageImpl.parseMessages(request.getReader());
        
        String[] batches=request.getParameterValues(MESSAGE_PARAM);
        
        if (batches == null || batches.length == 0)
            return null;

        if (batches.length == 1)
            return ServerMessageImpl.parseMessages(batches[0]);

        List<ServerMessage.Mutable> messages=new ArrayList<ServerMessage.Mutable>();
        for (int i=0; i < batches.length; i++)
        {
            if (batches[i] == null)
                continue;
            messages.addAll(Arrays.asList(ServerMessageImpl.parseMessages(batches[i])));
        }
        return messages.toArray(new ServerMessage.Mutable[messages.size()]);
    }
    
    /* ------------------------------------------------------------ */
    public void setCurrentRequest(HttpServletRequest request)
    {
        _currentRequest.set(request);
    }
    /* ------------------------------------------------------------ */

    public HttpServletRequest getCurrentRequest()
    {
        return _currentRequest.get();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.server.ServerTransport#getCurrentLocalAddress()
     */
    @Override
    public InetSocketAddress getCurrentLocalAddress()
    {
        HttpServletRequest request=getCurrentRequest();
        if (request!=null)
            return new InetSocketAddress(request.getLocalName(),request.getLocalPort());
        
        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.cometd.bayeux.server.ServerTransport#getCurrentRemoteAddress()
     */
    @Override
    public InetSocketAddress getCurrentRemoteAddress()
    {
        HttpServletRequest request=getCurrentRequest();
        if (request!=null)
            return new InetSocketAddress(request.getRemoteHost(),request.getRemotePort());
        
        return null;
    }
    
    
}
