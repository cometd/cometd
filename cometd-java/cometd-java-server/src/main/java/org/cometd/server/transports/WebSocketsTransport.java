package org.cometd.server.transports;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.server.BayeuxServerImpl;



public class WebSocketsTransport extends HttpTransport
{
    public final static String NAME="websockets";
    
    public WebSocketsTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux,NAME);
    
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // TODO Auto-generated method stub
        
    }
    
}
