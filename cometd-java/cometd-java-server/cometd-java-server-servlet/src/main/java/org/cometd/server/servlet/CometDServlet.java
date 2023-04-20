/*
 * Copyright (c) 2008-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cometd.server.servlet;

import java.io.IOException;
import java.util.Collections;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.cometd.server.spi.CometDException;
import org.cometd.server.spi.CometDRequest;
import org.cometd.server.spi.CometDResponse;
import org.cometd.server.spi.HttpException;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.http.AbstractHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The CometD Servlet maps HTTP requests to the {@link org.cometd.server.http.AbstractHttpTransport}
 * of a {@link BayeuxServer} instance.</p>
 * <p>The {@link BayeuxServer} instance is searched in the servlet context under the {@link BayeuxServer#ATTRIBUTE}
 * attribute; if it is found then it is used without further configuration, otherwise a new {@link BayeuxServer}
 * instance is created and configured using the init parameters of this servlet.</p>
 */
public class CometDServlet extends HttpServlet {
    private static final Logger LOGGER = LoggerFactory.getLogger(CometDServlet.class);

    private BayeuxServerImpl _bayeux;

    @Override
    public void init() throws ServletException {
        try {
            boolean export = false;
            _bayeux = (BayeuxServerImpl)getServletContext().getAttribute(BayeuxServer.ATTRIBUTE);
            if (_bayeux == null) {
                export = true;
                _bayeux = newBayeuxServer();

                // Transfer all servlet init parameters to the BayeuxServer implementation
                for (String initParamName : Collections.list(getInitParameterNames())) {
                    _bayeux.setOption(initParamName, getInitParameter(initParamName));
                }

                // Add the ServletContext to the options
                _bayeux.setOption(ServletContext.class.getName(), getServletContext());
            }

            _bayeux.start();

            if (export) {
                getServletContext().setAttribute(BayeuxServer.ATTRIBUTE, _bayeux);
            }
        } catch (Exception x) {
            throw new ServletException(x);
        }
    }

    public BayeuxServerImpl getBayeux() {
        return _bayeux;
    }

    protected BayeuxServerImpl newBayeuxServer() {
        return new BayeuxServerImpl();
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if ("OPTIONS".equals(request.getMethod())) {
            serviceOptions(request, response);
            return;
        }

        // API calls could be async, so we must be async in the request processing too.
        AsyncContext asyncContext = request.startAsync();
        // Explicitly disable the timeout, to prevent
        // that the timeout fires in case of slow reads.
        asyncContext.setTimeout(0);

        CometDRequest cometDRequest = new ServletCometDRequest(request);
        CometDResponse cometDResponse = new ServletCometDResponse(response);
        BayeuxContext bayeuxContext = new ServletBayeuxContext(request);

        Promise<Void> promise = new Promise<>() {
            @Override
            public void succeed(Void result) {
                asyncContext.complete();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Handling successful");
                }
            }

            @Override
            public void fail(Throwable failure) {
                int code = failure instanceof HttpException ?
                    ((HttpException)failure).getCode() :
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                sendError(request, response, code, failure);
                asyncContext.complete();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Handling failed", failure);
                }
            }
        };

        AbstractHttpTransport transport = _bayeux.findHttpTransport(cometDRequest);
        if (transport == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown Bayeux Transport");
        } else {
            try {
                transport.handle(bayeuxContext, cometDRequest, cometDResponse, promise);
            } catch (CometDException e) {
                throw new ServletException(e);
            }
        }
    }

    protected void serviceOptions(HttpServletRequest request, HttpServletResponse response) {
        // OPTIONS requests are made by browsers that are CORS compliant
        // (see http://www.w3.org/TR/cors/) during a "preflight request".
        // Preflight requests happen for each different new URL, then results are cached
        // by the browser.
        // For the Bayeux protocol, preflight requests happen for URLs such as
        // "/cometd/handshake", "/cometd/connect", etc, since the Bayeux clients append
        // the Bayeux message type to the base Bayeux server URL.
        // Just return 200 OK, there is nothing more to add to such requests.
    }

    protected void sendError(HttpServletRequest request, HttpServletResponse response, int code, Throwable failure) {
        try {
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
            response.setStatus(code);
        } catch (Throwable x) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("", x);
            }
        }
    }

    @Override
    public void destroy() {
        for (ServerSession session : _bayeux.getSessions()) {
            ((ServerSessionImpl)session).destroyScheduler();
        }

        try {
            _bayeux.stop();
        } catch (Exception x) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("", x);
            }
        } finally {
            _bayeux = null;
            getServletContext().removeAttribute(BayeuxServer.ATTRIBUTE);
        }
    }
}
