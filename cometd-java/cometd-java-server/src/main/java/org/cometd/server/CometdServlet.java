// ========================================================================
// Copyright 2007 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//========================================================================

package org.cometd.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Transport;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.transports.JSONPTransport;
import org.cometd.server.transports.HttpTransport;
import org.cometd.server.transports.JSONTransport;
import org.cometd.server.transports.WebSocketsTransport;

/**
 */
public class CometdServlet extends GenericServlet
{
    public static final String CLIENT_ATTR="org.cometd.server.client";
    public static final String TRANSPORT_ATTR="org.cometd.server.transport";
    public static final String MESSAGE_PARAM="message";
    public static final String TUNNEL_INIT_PARAM="tunnelInit";
    public static final String HTTP_CLIENT_ID="BAYEUX_HTTP_CLIENT";

    private final BayeuxServerImpl _bayeux = new BayeuxServerImpl();
    private final JSONTransport _lpTransport = new JSONTransport(_bayeux,_bayeux.getOptions());
    private final JSONPTransport _cbTransport = new JSONPTransport(_bayeux,_bayeux.getOptions());
    private final WebSocketsTransport _wsTransport = new WebSocketsTransport(_bayeux,_bayeux.getOptions());
    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<HttpServletRequest>();
    private String _transportParameter;
    private String _callbackParameter;
    private boolean _useWS;
    private boolean _useLP;
    private boolean _useCB;
    private int _logLevel;
    
    
    public BayeuxServerImpl getBayeux()
    {
        return _bayeux;
    }

    protected void initializeBayeux(BayeuxServerImpl bayeux)
    {
        bayeux.addTransport(_wsTransport);
        bayeux.addTransport(_lpTransport);
        bayeux.addTransport(_cbTransport);
        bayeux.setAllowedTransports(WebSocketsTransport.NAME,JSONTransport.NAME,JSONPTransport.NAME);
    }

    @Override
    public void init() throws ServletException
    {
        initializeBayeux(_bayeux);
        getServletContext().setAttribute(BayeuxServer.ATTRIBUTE,_bayeux);
        
        if (getServletConfig().getInitParameter("logLevel")!=null)
            _logLevel=Integer.parseInt(getServletConfig().getInitParameter("logLevel"));
        
        // Get any specific options as init paramters
        HashSet<String> qualified_names = new HashSet<String>();
        for (String name :_bayeux.getKnownTransportNames())
        {
            Transport transport = _bayeux.getTransport(name);
            {
                for (String option : transport.getOptionNames())
                {
                    qualified_names.add(option);
                    String prefix=transport.getOptionPrefix();
                    while (prefix!=null)
                    {
                        qualified_names.add(prefix+"."+option);
                        int dot=prefix.lastIndexOf('.');
                        prefix=dot<0?null:prefix.substring(0,dot);
                    }
                }
            }
        }
        
        for (String option : qualified_names)
        {
            Object value = getServletContext().getInitParameter(option);
            if (value!=null)
                _bayeux.setOption(option,value);
        }

        for (String name :_bayeux.getKnownTransportNames())
        {
            Transport transport = _bayeux.getTransport(name);
            if (transport instanceof ServerTransport)
                ((ServerTransport)transport).init();
        }
        
        if (_logLevel>0)
        {
            for (Map.Entry<String, Object> entry : _bayeux.getOptions().entrySet())
                getServletContext().log(entry.getKey()+"="+entry.getValue());
        }
        
        _useLP=_bayeux.getAllowedTransports().contains(JSONTransport.NAME);
        _useCB=_bayeux.getAllowedTransports().contains(JSONPTransport.NAME);
        _useWS=_bayeux.getAllowedTransports().contains(JSONPTransport.NAME);
        _callbackParameter=(String)_cbTransport.getCallbackParameter();
        
        try
        {
            _bayeux.start();
        }
        catch(Exception e)
        {
            throw new ServletException(e);
        }
    }

    @Override
    public void service(ServletRequest req, ServletResponse resp) throws ServletException, IOException
    {
        HttpServletRequest request=(HttpServletRequest)req;
        HttpServletResponse response=(HttpServletResponse)resp;

        _currentRequest.set(request);
        try
        {
            service(request,response);
        }
        finally
        {
            _currentRequest.set(null);
        }
    }

    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        HttpTransport transport=null;
        
        // handle forced transport
        if (_transportParameter!=null)
        {
            String transport_name=request.getParameter(_transportParameter);
            if (transport_name!=null)
                transport= (HttpTransport)_bayeux.getTransport(transport_name);
        }
        
        if (transport==null)
        {
            if (_useCB && request.getParameter(_callbackParameter)!=null)
                transport=_cbTransport;
            else if (_useWS && "WebSocket".equals(request.getHeader("Upgrade")))
                transport=_wsTransport;
            else if (_useLP)
                transport=_lpTransport;
        }
            
        if (transport==null)         
            response.sendError(400,"bad transport");
        else
        {
            try
            {
                _bayeux.setCurrentTransport(transport);
                transport.setCurrentRequest(request);
                transport.handle(request,response);
            }
            finally
            {
                _bayeux.setCurrentTransport(null);  
                transport.setCurrentRequest(null);
            }
        }
    }


    private static ServerMessage.Mutable[] __EMPTY_BATCH=new ServerMessage.Mutable[0];

    protected ServerMessage.Mutable[] getMessages(HttpServletRequest request) throws IOException
    {
        String fodder=null;
        try
        {
            // Get message batches either as JSON body or as message parameters
            if (request.getContentType() != null && !request.getContentType().startsWith("application/x-www-form-urlencoded"))
            {
                return _bayeux.getServerMessagePool().parse(request.getReader());
            }

            String[] batches=request.getParameterValues(MESSAGE_PARAM);

            if (batches == null || batches.length == 0)
                return __EMPTY_BATCH;

            if (batches.length == 0)
            {
                fodder=batches[0];
                return _bayeux.getServerMessagePool().parse(fodder);
            }

            throw new IllegalStateException();
        }
        catch(IOException e)
        {
            throw e;
        }
        catch(Exception e)
        {
            throw new Error(fodder,e);
        }
    }

}
