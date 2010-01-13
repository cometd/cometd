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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.Transport;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.transports.CallbackPollingHttpTransport;
import org.cometd.server.transports.DefaultTransport;
import org.cometd.server.transports.HttpTransport;
import org.cometd.server.transports.LongPollingHttpTransport;
import org.cometd.server.transports.WebSocketsTransport;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;

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
    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<HttpServletRequest>();
    private final DefaultTransport _dftTransport = new DefaultTransport();
    private final LongPollingHttpTransport _lpTransport = new LongPollingHttpTransport(_bayeux);
    private final CallbackPollingHttpTransport _cbTransport = new CallbackPollingHttpTransport(_bayeux);
    private final WebSocketsTransport _wsTransport = new WebSocketsTransport(_bayeux);
    private String _transportParameter;
    private String _callbackParameter;
    private boolean _useWS;
    private boolean _useLP;
    private boolean _useCB;
    
    
    public BayeuxServerImpl getBayeux()
    {
        return _bayeux;
    }

    protected void initializeBayeux(BayeuxServerImpl bayeux)
    {
        bayeux.addTransport(_dftTransport);
        bayeux.addTransport(_wsTransport);
        bayeux.addTransport(_lpTransport);
        bayeux.addTransport(_cbTransport);
        bayeux.setAllowedTransports(WebSocketsTransport.NAME,LongPollingHttpTransport.NAME,CallbackPollingHttpTransport.NAME);
    }

    @Override
    public void init() throws ServletException
    {
        initializeBayeux(_bayeux);
        getServletContext().setAttribute(BayeuxServer.ATTRIBUTE,_bayeux);
        
        // get the default options as init parameters
        Transport dft = _bayeux.getTransport("*");
        for (String option : dft.getMutableOptions())
        {
            String value=getServletContext().getInitParameter(option);
            if (value!=null)
            {
                
                Object old=dft.getOptions().get(option);
                if (old==null)
                    dft.getOptions().put(option,value);
                else if (old instanceof Integer)
                    dft.getOptions().put(option,Integer.parseInt(value));
                else if (old instanceof Long)
                    dft.getOptions().put(option,Long.parseLong(value));
                else if (old instanceof Double)
                    dft.getOptions().put(option,Double.parseDouble(value));
                else if (old instanceof Boolean)
                    dft.getOptions().put(option,Boolean.parseBoolean(value));
                else if ("none".equals(value))
                    dft.getOptions().remove(option);
                else
                    dft.getOptions().put(option,value);
            }
        }
        
        // Get any specific options as init paramters
        for (String name :_bayeux.getKnownTransportNames())
        {
            Transport transport = _bayeux.getTransport(name);
            for (String option : transport.getMutableOptions())
            {
                String value=getServletContext().getInitParameter(transport.getName()+"."+option);
                if (value!=null)
                {
                    Object old=transport.getOptions().get(option);
                    if (old==null)
                        transport.getOptions().put(option,value);
                    else if (old instanceof Integer)
                        transport.getOptions().put(option,Integer.parseInt(value));
                    else if (old instanceof Long)
                        transport.getOptions().put(option,Long.parseLong(value));
                    else if (old instanceof Double)
                        transport.getOptions().put(option,Double.parseDouble(value));
                    else if (old instanceof Boolean)
                        transport.getOptions().put(option,Boolean.parseBoolean(value));
                    else if ("none".equals(value))
                        dft.getOptions().remove(option);
                    else
                        transport.getOptions().put(option,value);
                }
            }
        }
        
        _transportParameter=(String)_dftTransport.getOptions().get(DefaultTransport.TRANSPORT_PARAMETER_OPTION);
        _useLP=_bayeux.getAllowedTransports().contains(LongPollingHttpTransport.NAME);
        _useCB=_bayeux.getAllowedTransports().contains(CallbackPollingHttpTransport.NAME);
        _useWS=_bayeux.getAllowedTransports().contains(CallbackPollingHttpTransport.NAME);
        _callbackParameter=(String)_cbTransport.getOptions().get(CallbackPollingHttpTransport.CALLBACK_PARAMETER_OPTION);
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
        // handle forced transport
        if (_transportParameter!=null)
        {
            String transport_name=request.getParameter(_transportParameter);
            if (transport_name!=null)
            {
                Transport transport= _bayeux.getTransport(transport_name);
                if (!(transport instanceof HttpTransport))
                    response.sendError(400,"bad transport");
                else
                    ((HttpTransport)transport).handle(request,response);
                return;
            }
        }
        
        if (_useCB)
        {
            String callback=request.getParameter(_callbackParameter);
            if (callback!=null)
            {
                _cbTransport.handle(request,response);
                return;
            }
        }

        if (_useWS && "WebSocket".equals(request.getHeader("Upgrade")))
        {
            _wsTransport.handle(request,response);
            return;
        }
        
        if (_useLP)
        {
            _lpTransport.handle(request,response);
            return;
        }
            
        response.sendError(400,"bad transport");
        
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

            List<ServerMessage.Mutable> messages=new ArrayList<ServerMessage.Mutable>();
            for (int i=0; i < batches.length; i++)
            {
                if (batches[i] == null)
                    continue;

                fodder=batches[i];
                // TODO
                // _bayeux.parseTo(fodder,messages);

            }

            return messages.toArray(new ServerMessage.Mutable[messages.size()]);
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
