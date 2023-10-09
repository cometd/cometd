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
package org.cometd.server.http.jetty;

import java.util.Map;
import java.util.Objects;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.HttpException;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.transport.AbstractHttpTransport;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>{@code CometDHandler} maps HTTP requests to the HTTP server transports
 * of a {@link BayeuxServer} instance.</p>
 * <p>The {@link BayeuxServer} instance is created and configured using the
 * given {@link #setOptions(Map) options}.</p>
 */
public class CometDHandler extends Handler.Abstract {
    private static final Logger LOGGER = LoggerFactory.getLogger(CometDHandler.class);

    private Map<String, String> options = Map.of();
    private BayeuxServer bayeuxServer;

    public Map<String, String> getOptions() {
        return options;
    }

    public void setOptions(Map<String, String> options) {
        this.options = Objects.requireNonNull(options);
    }

    @Override
    protected void doStart() throws Exception {
        bayeuxServer = newBayeuxServer();
        addBean(bayeuxServer);

        bayeuxServer.setOption(BayeuxServerImpl.TRANSPORTS_OPTION, """
                org.cometd.server.http.jetty.JettyJSONTransport,
                org.cometd.server.websocket.jetty.JettyWebSocketTransport
                """);
        for (Map.Entry<String, String> entry : getOptions().entrySet()) {
            bayeuxServer.setOption(entry.getKey(), entry.getValue());
        }

        Context context = ContextHandler.getCurrentContext(getServer());
        bayeuxServer.setOption(Context.class.getName(), context);
        context.setAttribute(BayeuxServer.ATTRIBUTE, bayeuxServer);

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception {
        for (ServerSession session : bayeuxServer.getSessions()) {
            ((ServerSessionImpl)session).destroyScheduler();
        }
        super.doStop();
        removeBean(bayeuxServer);
    }

    public BayeuxServer getBayeuxServer() {
        return bayeuxServer;
    }

    protected BayeuxServer newBayeuxServer() {
        return new BayeuxServerImpl();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        if ("OPTIONS".equals(request.getMethod())) {
            serviceOptions(request, response, callback);
            return true;
        }

        JettyCometDRequest cometDRequest = new JettyCometDRequest(request);
        JettyCometDResponse cometDResponse = new JettyCometDResponse(response);
        JettyBayeuxContext bayeuxContext = new JettyBayeuxContext(cometDRequest, request);

        Promise<Void> promise = new Promise<>() {
            @Override
            public void succeed(Void result) {
                callback.succeeded();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Handling successful");
                }
            }

            @Override
            public void fail(Throwable failure) {
                int code = failure instanceof HttpException http ? http.getCode() : HttpStatus.INTERNAL_SERVER_ERROR_500;
                sendError(request, response, callback, code, failure);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Handling failed", failure);
                }
            }
        };

        AbstractHttpTransport transport = AbstractHttpTransport.find(bayeuxServer, cometDRequest);
        if (transport == null) {
            Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400, "Unknown Bayeux Transport");
        } else {
            transport.handle(bayeuxContext, cometDRequest, cometDResponse, promise);
        }
        return true;
    }

    protected void serviceOptions(Request request, Response response, Callback callback) {
        // OPTIONS requests are made by browsers that are CORS compliant
        // (see http://www.w3.org/TR/cors/) during a "preflight request".
        // Preflight requests happen for each different new URL, then
        // results are cached by the browser.
        // For the Bayeux protocol, preflight requests happen for URLs
        // such as "/cometd/handshake", "/cometd/connect", etc., since
        // the Bayeux clients append the Bayeux message type to the base
        // Bayeux server URL.
        // Just return 200 OK, there is nothing more to add to such requests.
        callback.succeeded();
    }

    protected void sendError(Request request, Response response, Callback callback, int code, Throwable failure) {
        Response.writeError(request, response, callback, code);
    }
}
