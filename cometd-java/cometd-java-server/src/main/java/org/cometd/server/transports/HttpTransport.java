package org.cometd.server.transports;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    protected final static String BROWSER_ID_OPTION="browserId";
    protected String _browserId="BAYEUX_BROWSER";
    
    protected HttpTransport(BayeuxServerImpl bayeux, DefaultTransport dftTransport,String name)
    {
        super(bayeux,dftTransport,name);
        _mutable.addAll(dftTransport.getMutableOptions());
    }
    
    @Override
    protected void init()
    {
        super.init();
        _browserId=getOption(BROWSER_ID_OPTION,_browserId);
    }

    public abstract void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;
    
    protected String findBrowserId(HttpServletRequest request)
    {
        Cookie[] cookies=request.getCookies();
        if (cookies != null)
        {
            for (Cookie cookie : cookies)
            {
                if (_browserId.equals(cookie.getName()))
                    return cookie.getValue();
            }
        }
        return null;
    }

    protected String setBrowserId(HttpServletRequest request, HttpServletResponse response)
    {
        String browser_id=Long.toHexString(request.getRemotePort()) + Long.toString(_bayeux.randomLong(),36) + Long.toString(System.currentTimeMillis(),36)
                + Long.toString(request.getRemotePort(),36);
        Cookie cookie=new Cookie(_browserId,browser_id);
        cookie.setPath("/");
        cookie.setMaxAge(-1);
        response.addCookie(cookie);
        return browser_id;
    }

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
    
    
}
