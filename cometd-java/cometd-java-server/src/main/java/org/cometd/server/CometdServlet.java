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
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerTransport;
import org.cometd.server.transport.HttpTransport;

/**
 * The cometd Servlet.
 * </p>
 * <p>
 * The cometd Servlet maps HTTP requests to the {@link HttpTransport} of a {@link BayeuxServerImpl} instance.
 * If a {@link BayeuxServerImpl} instance is discovered in the {@link BayeuxServer#ATTRIBUTE} servlet context
 * attribute, then it is used, otherwise a new instance is created and the {@link #initializeBayeux(BayeuxServerImpl)}
 * method called.
 * </p>
 * <p>
 *
 * </p>
 */
public class CometdServlet extends GenericServlet
{
    private static final long serialVersionUID = 3637310585741732936L;
    /**
     * @deprecated Use {@link BayeuxServerImpl#CONFIG_LOG_LEVEL} instead.
     */
    @Deprecated
    public static final int CONFIG_LEVEL=BayeuxServerImpl.CONFIG_LOG_LEVEL;
    /**
     * @deprecated Use {@link BayeuxServerImpl#INFO_LOG_LEVEL} instead.
     */
    @Deprecated
    public static final int INFO_LEVEL=BayeuxServerImpl.INFO_LOG_LEVEL;
    /**
     * @deprecated Use {@link BayeuxServerImpl#DEBUG_LOG_LEVEL} instead.
     */
    @Deprecated
    public static final int DEBUG_LEVEL=BayeuxServerImpl.DEBUG_LOG_LEVEL;

    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<HttpServletRequest>();
    private final List<HttpTransport> _transports = new ArrayList<HttpTransport>();
    private volatile BayeuxServerImpl _bayeux;

    /* ------------------------------------------------------------ */
    @Override
    public void init() throws ServletException
    {
        try
        {
            boolean export = false;
            _bayeux = (BayeuxServerImpl)getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
            if (_bayeux == null)
            {
                export = true;
                _bayeux = newBayeuxServer();

                // Transfer all servlet init parameters to the BayeuxServer implementation
                for (Enumeration initParameterNames = getInitParameterNames(); initParameterNames.hasMoreElements();)
                {
                    String initParameterName = (String)initParameterNames.nextElement();
                    _bayeux.setOption(initParameterName, getInitParameter(initParameterName));
                }
            }

            _bayeux.start();

            if (export)
                getServletContext().setAttribute(BayeuxServer.ATTRIBUTE, _bayeux);

            for (String transportName : _bayeux.getAllowedTransports())
            {
                ServerTransport transport = _bayeux.getTransport(transportName);
                if (transport instanceof HttpTransport)
                    _transports.add((HttpTransport)transport);
            }
        }
        catch (Exception x)
        {
            throw new ServletException(x);
        }
    }

    public BayeuxServerImpl getBayeux()
    {
        return _bayeux;
    }

    protected BayeuxServerImpl newBayeuxServer()
    {
        return new BayeuxServerImpl();
    }

    public List<HttpTransport> getTransports()
    {
        return Collections.unmodifiableList(_transports);
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
        if ("OPTIONS".equals(request.getMethod()))
        {
            serviceOptions(request, response);
            return;
        }

        HttpTransport transport=null;
        for (HttpTransport t : _transports)
        {
            if (t!=null && t.accept(request))
            {
                transport=t;
                break;
            }
        }

        if (transport==null)
        {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown Bayeux Transport");
        }
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

    protected void serviceOptions(HttpServletRequest request, HttpServletResponse response)
    {
        // OPTIONS requests are made by browsers that are CORS compliant
        // (see http://www.w3.org/TR/cors/) during a "preflight request".
        // Preflight requests happen for each different new URL, then results are cached
        // by the browser.
        // For the Bayeux protocol, preflight requests happen for URLs such as
        // "/cometd/handshake", "/cometd/connect", etc, since the Bayeux clients append
        // the Bayeux message type to the base Bayeux server URL.
        // Just return 200 OK, there is nothing more to add to such requests.
    }

    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.GenericServlet#destroy()
     */
    @Override
    public void destroy()
    {
        for (ServerSessionImpl session : _bayeux.getSessions())
        {
            session.cancelSchedule();
        }

        try
        {
            _bayeux.stop();
        }
        catch (Exception x)
        {
            _bayeux.getLogger().debug(x);
        }
        finally
        {
            _bayeux = null;
        }

        _transports.clear();
    }
}
