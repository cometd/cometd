package org.cometd.server.transports;

import java.io.IOException;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.server.BayeuxServerImpl;



public class WebSocketsTransport extends HttpTransport
{
    public final static String NAME="websockets";
    public final static String PROTOCOL_OPTION="protocol";
    
    String _protocol="bayeux";
    
    public WebSocketsTransport(BayeuxServerImpl bayeux, Map<String,Object> options)
    {
        super(bayeux,NAME,options);
        _prefix.add("ws");
        setOption(PROTOCOL_OPTION,_protocol);
    
    }

    @Override
    public void init()
    {
        _protocol=getOption(PROTOCOL_OPTION,_protocol);
    }
    
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        
    }
    
}
