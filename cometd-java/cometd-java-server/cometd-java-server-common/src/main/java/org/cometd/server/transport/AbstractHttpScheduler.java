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
package org.cometd.server.transport;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.CometDRequest;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractHttpScheduler implements Runnable, AbstractHttpTransport.HttpScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractHttpScheduler.class);

    private final AtomicReference<Scheduler.Task> task = new AtomicReference<>();
    private final AbstractHttpTransport transport;
    private final TransportContext context;
    private final Promise<Void> promise;
    private final ServerMessage.Mutable message;

    protected AbstractHttpScheduler(AbstractHttpTransport transport, TransportContext context, Promise<Void> promise, ServerMessage.Mutable message, long timeout) {
        this.transport = transport;
        this.context = context;
        this.promise = promise;
        this.message = message;
        this.task.set(transport.getBayeux().schedule(this, timeout));
        context.metaConnectCycle(transport.newMetaConnectCycle());
    }

    public TransportContext getContext() {
        return context;
    }

    public Promise<Void> getPromise() {
        return promise;
    }

    @Override
    public ServerMessage.Mutable getMessage() {
        return message;
    }

    @Override
    public long getMetaConnectCycle() {
        return context.metaConnectCycle();
    }

    @Override
    public void schedule() {
        if (cancelTimeout()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Resuming suspended {} for {}", message, context.session());
            }
            resume(false);
        }
    }

    @Override
    public void cancel() {
        if (cancelTimeout()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Cancelling suspended {} for {}", message, context.session());
            }
            error(new TimeoutException());
        }
    }

    @Override
    public void destroy() {
        cancel();
    }

    private boolean cancelTimeout() {
        // Cannot rely on the return value of task.cancel()
        // since it may be invoked when the task is in run()
        // where cancellation is not possible (it's too late).
        Scheduler.Task task = this.task.getAndSet(null);
        if (task == null) {
            return false;
        }
        task.cancel();
        return true;
    }

    @Override
    public void run() {
        if (cancelTimeout()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Timing out suspended {} for {}", message, context.session());
            }
            resume(true);
        }
    }

    private void resume(boolean timeout) {
        transport.decBrowserId(context.session(), transport.isHTTP2(context.request()));
        dispatch(timeout);
    }

    protected abstract void dispatch(boolean timeout);

    protected void error(Throwable failure) {
        CometDRequest request = context.request();
        transport.decBrowserId(context.session(), transport.isHTTP2(request));
        getPromise().fail(failure);
    }

    @Override
    public String toString() {
        return String.format("%s@%x[cycle=%d]", getClass().getSimpleName(), hashCode(), getMetaConnectCycle());
    }
}
