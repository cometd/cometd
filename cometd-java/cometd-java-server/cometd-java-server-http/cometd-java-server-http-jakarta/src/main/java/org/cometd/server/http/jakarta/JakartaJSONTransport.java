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
package org.cometd.server.http.jakarta;

import jakarta.servlet.RequestDispatcher;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.CometDRequest;
import org.cometd.server.CometDResponse;
import org.cometd.server.transport.AbstractJSONTransport;
import org.cometd.server.transport.TransportContext;

public class JakartaJSONTransport extends AbstractJSONTransport {
    public JakartaJSONTransport(BayeuxServerImpl bayeux) {
        super(bayeux);
    }

    @Override
    public void handle(BayeuxContext bayeuxContext, CometDRequest request, CometDResponse response, Promise<Void> promise) {
        super.handle(bayeuxContext, request, response, new Promise.Wrapper<>(promise) {
            @Override
            public void fail(Throwable failure) {
                request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
                super.fail(failure);
            }
        });
    }

    @Override
    protected HttpScheduler newHttpScheduler(TransportContext context, Promise<Void> promise, ServerMessage.Mutable reply, long timeout) {
        return new JakartaHttpScheduler(this, context, promise, reply, timeout);
    }
}
