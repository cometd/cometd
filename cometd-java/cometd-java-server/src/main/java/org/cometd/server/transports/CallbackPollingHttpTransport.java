package org.cometd.server.transports;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.server.BayeuxServerImpl;


public class CallbackPollingHttpTransport extends HttpTransport
{
    public final static String NAME="callback-polling";
    public final static String CALLBACK_PARAMETER_OPTION="callbackParameter";
    
    public CallbackPollingHttpTransport(BayeuxServerImpl bayeux,DefaultTransport dftTransport)
    {
        super(bayeux,dftTransport,NAME);
        getOptions().put(CALLBACK_PARAMETER_OPTION,"jsonp");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // TODO Auto-generated method stub
        
    }
}
