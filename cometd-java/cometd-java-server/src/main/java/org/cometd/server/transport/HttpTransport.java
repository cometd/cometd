package org.cometd.server.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerMessageImpl;


public abstract class HttpTransport extends AbstractServerTransport
{
    public static final String JSON_DEBUG_OPTION="jsonDebug";
    public static final String MESSAGE_PARAM="message";

    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<HttpServletRequest>();
    private boolean _jsonDebug = false;

    protected HttpTransport(BayeuxServerImpl bayeux,String name)
    {
        super(bayeux,name);
        setOption(JSON_DEBUG_OPTION, _jsonDebug);
    }

    @Override
    protected void init()
    {
        super.init();
        _jsonDebug = getOption(JSON_DEBUG_OPTION, _jsonDebug);
    }

    public abstract boolean accept(HttpServletRequest request);

    public abstract void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;

    protected ServerMessage.Mutable[] parseMessages(HttpServletRequest request)
            throws IOException, ParseException
    {
        String content_type=request.getContentType();

        // Get message batches either as JSON body or as message parameters
        if (content_type!=null && !content_type.startsWith("application/x-www-form-urlencoded"))
            return ServerMessageImpl.parseMessages(request.getReader(), _jsonDebug);

        String[] batches=request.getParameterValues(MESSAGE_PARAM);

        if (batches == null || batches.length == 0)
            return null;

        if (batches.length == 1)
            return ServerMessageImpl.parseMessages(batches[0]);

        List<ServerMessage.Mutable> messages=new ArrayList<ServerMessage.Mutable>();
        for (String batch : batches)
        {
            if (batch == null)
                continue;
            messages.addAll(Arrays.asList(ServerMessageImpl.parseMessages(batch)));
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
