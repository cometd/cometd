/*
 * Copyright (c) 2010 the original author or authors.
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

package org.cometd.websocket.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.MessageHandler;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;

public class WebSocketTransport extends AbstractWebSocketTransport<Session>
{
    public WebSocketTransport(BayeuxServerImpl bayeux)
    {
        super(bayeux);
    }

    @Override
    protected void init()
    {
        super.init();

        final ServletContext context = (ServletContext)getOption(ServletContext.class.getName());
        if (context == null)
            throw new IllegalArgumentException("Missing ServletContext");

        String cometdURLMapping = (String)getOption(COMETD_URL_MAPPING);
        if (cometdURLMapping == null)
            throw new IllegalArgumentException("Missing URL Mapping");

        if (cometdURLMapping.endsWith("/*"))
            cometdURLMapping = cometdURLMapping.substring(0, cometdURLMapping.length() - 2);

        ServerContainer container = (ServerContainer)context.getAttribute(ServerContainer.class.getName());
        // JSR 356 does not support a input buffer size option
        int maxMessageSize = getOption(MAX_MESSAGE_SIZE_OPTION, container.getDefaultMaxTextMessageBufferSize());
        container.setDefaultMaxTextMessageBufferSize(maxMessageSize);
        long idleTimeout = getOption(IDLE_TIMEOUT_OPTION, container.getDefaultMaxSessionIdleTimeout());
        container.setDefaultMaxSessionIdleTimeout(idleTimeout);

        String protocol = getProtocol();
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(WebSocketScheduler.class, cometdURLMapping)
                .subprotocols(protocol == null ? null : Collections.singletonList(protocol))
                .configurator(new Configurator(context))
                .build();

        try
        {
            container.addEndpoint(config);
        }
        catch (DeploymentException x)
        {
            throw new RuntimeException(x);
        }
    }

    protected boolean checkOrigin(HandshakeRequest request, String origin)
    {
        return true;
    }

    protected void send(final Session wsSession, final ServerSession session, String data) throws IOException
    {
        // TODO: offer a switch to send sync or async

        _logger.debug("Sending {}", data);
        // Blocking write.
        // We trade - for now - a blocked thread with the frame queue growing
        // and consequent increased message latency (messages sit in the queue).
//        session.getBasicRemote().sendText(data);

        // Async write.
        wsSession.getAsyncRemote().sendText(data, new SendHandler()
        {
            @Override
            public void onResult(SendResult result)
            {
                Throwable failure = result.getException();
                if (failure != null)
                    handleException(wsSession, session, failure);
            }
        });
    }

    private class WebSocketScheduler extends Endpoint implements AbstractServerTransport.Scheduler, Runnable, MessageHandler.Whole<String>
    {
        private final AbstractWebSocketScheduler delegate;
        private volatile Session _wsSession;

        private WebSocketScheduler(WebSocketContext context)
        {
            delegate = new AbstractWebSocketScheduler(context)
            {
                @Override
                protected void close(final int code, String reason)
                {
                    try
                    {
                        _wsSession.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), reason));
                    }
                    catch (IOException x)
                    {
                        _logger.trace("Could not close WebSocket session " + _wsSession, x);
                    }
                }

                @Override
                protected void schedule(boolean timeout, ServerMessage.Mutable expiredConnectReply)
                {
                    schedule(_wsSession, timeout, expiredConnectReply);
                }
            };
        }

        @Override
        public void onOpen(Session wsSession, EndpointConfig config)
        {
            _wsSession = wsSession;
            wsSession.addMessageHandler(this);
        }

        @Override
        public void onClose(Session wsSession, CloseReason closeReason)
        {
            delegate.onClose(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
        }

        @Override
        public void onError(Session wsSession, Throwable failure)
        {
            delegate.onError(failure);
            // TODO: more to do ?
        }

        @Override
        public void cancel()
        {
            delegate.cancel();
        }

        @Override
        public void schedule()
        {
            delegate.schedule();
        }

        @Override
        public void run()
        {
            delegate.run();
        }

        @Override
        public void onMessage(String data)
        {
            _logger.debug("WebSocket Text message on {}/{}", WebSocketTransport.this.hashCode(), hashCode());
            delegate.onMessage(_wsSession, data);
        }
    }

    private class WebSocketContext implements BayeuxContext
    {
        private final ServletContext context;
        private final HandshakeRequest request;

        private WebSocketContext(ServletContext context, HandshakeRequest request)
        {
            this.context = context;
            this.request = request;
        }

        @Override
        public Principal getUserPrincipal()
        {
            return request.getUserPrincipal();
        }

        @Override
        public boolean isUserInRole(String role)
        {
            return request.isUserInRole(role);
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            // Not available in JSR 356
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            // Not available in JSR 356
            return null;
        }

        @Override
        public String getHeader(String name)
        {
            List<String> headers = request.getHeaders().get(name);
            return headers != null && headers.size() > 0 ? headers.get(0) : null;
        }

        @Override
        public List<String> getHeaderValues(String name)
        {
            return request.getHeaders().get(name);
        }

        public String getParameter(String name)
        {
            List<String> params = request.getParameterMap().get(name);
            return params != null && params.size() > 0 ? params.get(0) : null;
        }

        @Override
        public List<String> getParameterValues(String name)
        {
            return request.getParameterMap().get(name);
        }

        @Override
        public String getCookie(String name)
        {
            // TODO:
            return null;
        }

        @Override
        public String getHttpSessionId()
        {
            HttpSession session = (HttpSession)request.getHttpSession();
            return session == null ? null : session.getId();
        }

        @Override
        public Object getHttpSessionAttribute(String name)
        {
            HttpSession session = (HttpSession)request.getHttpSession();
            return session == null ? null : session.getAttribute(name);
        }

        @Override
        public void setHttpSessionAttribute(String name, Object value)
        {
            HttpSession session = (HttpSession)request.getHttpSession();
            if (session != null)
                session.setAttribute(name, value);
        }

        @Override
        public void invalidateHttpSession()
        {
            HttpSession session = (HttpSession)request.getHttpSession();
            if (session != null)
                session.invalidate();
        }

        @Override
        public Object getRequestAttribute(String name)
        {
            // Not available in JSR 356
            return null;
        }

        @Override
        public Object getContextAttribute(String name)
        {
            return context.getAttribute(name);
        }

        @Override
        public String getContextInitParameter(String name)
        {
            return context.getInitParameter(name);
        }

        @Override
        public String getURL()
        {
            String url = request.getRequestURI().toString();
            String query = request.getQueryString();
            if (query != null)
                url += "?" + query;
            return url;
        }
    }

    private class Configurator extends ServerEndpointConfig.Configurator
    {
        private final ServletContext servletContext;
        private WebSocketContext bayeuxContext;
        private boolean protocolMatches;

        private Configurator(ServletContext servletContext)
        {
            this.servletContext = servletContext;
        }

        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            this.bayeuxContext = new WebSocketContext(servletContext, request);
        }

        @Override
        public boolean checkOrigin(String originHeaderValue)
        {
            return WebSocketTransport.this.checkOrigin(bayeuxContext.request, originHeaderValue);
        }

        @Override
        public String getNegotiatedSubprotocol(List<String> supported, List<String> requested)
        {
            if (protocolMatches = checkProtocol(supported, requested))
                return super.getNegotiatedSubprotocol(supported, requested);
            _logger.warn("Could not negotiate WebSocket SubProtocols: server{} != client[]", supported, requested);
            return null;
        }

        @Override
        public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested)
        {
            return super.getNegotiatedExtensions(installed, requested);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException
        {
            if (!getBayeux().getAllowedTransports().contains(getName()))
                throw new InstantiationException("Transport not allowed");
            if (!protocolMatches)
                throw new InstantiationException("Could not negotiate WebSocket SubProtocols");
            return (T)new WebSocketScheduler(bayeuxContext);
        }
    }
}
