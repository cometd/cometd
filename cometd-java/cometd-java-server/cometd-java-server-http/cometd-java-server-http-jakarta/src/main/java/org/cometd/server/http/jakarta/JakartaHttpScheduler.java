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

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.transport.AbstractHttpScheduler;
import org.cometd.server.transport.AbstractHttpTransport;
import org.cometd.server.transport.TransportContext;

public class JakartaHttpScheduler extends AbstractHttpScheduler implements AsyncListener {
    public JakartaHttpScheduler(AbstractHttpTransport transport, TransportContext context, Promise<Void> promise, ServerMessage.Mutable reply, long timeout) {
        super(transport, context, promise, reply, timeout);
        AsyncContext asyncContext = (AsyncContext)context.request().getAttribute(AsyncContext.class.getName());
        if (asyncContext != null) {
            asyncContext.addListener(this);
        }
    }

    @Override
    public void onComplete(AsyncEvent event) {
    }

    @Override
    public void onTimeout(AsyncEvent event) {
    }

    @Override
    public void onError(AsyncEvent event) {
        error(event.getThrowable());
    }

    @Override
    public void onStartAsync(AsyncEvent event) {
    }

    @Override
    protected void dispatch(boolean timeout) {
        // Directly succeeding the callback to write messages and replies.
        // Since the write is async, we will never block and thus never delay other sessions.
        getContext().session().notifyResumed(getMessage(), timeout);
        getPromise().succeed(null);
    }
}
