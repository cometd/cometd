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
package org.cometd.server.servlet.transport;

import java.util.ArrayList;
import java.util.List;

import org.cometd.server.spi.CometDRequest;
import org.cometd.server.spi.CometDResponse;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.ServerSessionImpl;
import org.cometd.server.http.AbstractHttpTransport;

class ContextInServlet implements AbstractHttpTransport.Context {
    private final List<ServerMessage.Mutable> replies = new ArrayList<>();
    private final CometDRequest request;
    private final CometDResponse response;
    private final BayeuxContext bayeuxContext;
    private List<ServerMessage.Mutable> messages;
    private ServerSessionImpl session;
    private boolean sendQueue;
    private boolean scheduleExpiration;
    private AbstractHttpTransport.HttpScheduler scheduler;
    private long metaConnectCycle;

    public ContextInServlet(CometDRequest request, CometDResponse response, BayeuxContext bayeuxContext) {
        this.request = request;
        this.response = response;
        this.bayeuxContext = bayeuxContext;
    }

    @Override
    public BayeuxContext bayeuxContext()
    {
        return bayeuxContext;
    }

    @Override
    public List<ServerMessage.Mutable> messages()
    {
        return messages;
    }

    @Override
    public void messages(List<ServerMessage.Mutable> messages)
    {
        this.messages = messages;
    }

    @Override
    public long metaConnectCycle()
    {
        return metaConnectCycle;
    }

    @Override
    public void metaConnectCycle(long l)
    {
        this.metaConnectCycle = l;
    }

    @Override
    public List<ServerMessage.Mutable> replies()
    {
        return replies;
    }

    @Override
    public CometDRequest request()
    {
        return request;
    }

    @Override
    public CometDResponse response()
    {
        return response;
    }

    @Override
    public void scheduleExpiration(boolean b)
    {
        this.scheduleExpiration = b;
    }

    @Override
    public boolean scheduleExpiration()
    {
        return scheduleExpiration;
    }

    @Override
    public AbstractHttpTransport.HttpScheduler scheduler()
    {
        return scheduler;
    }

    @Override
    public void scheduler(AbstractHttpTransport.HttpScheduler httpScheduler)
    {
        this.scheduler = httpScheduler;
    }

    @Override
    public void sendQueue(boolean b)
    {
        this.sendQueue = b;
    }

    @Override
    public boolean sendQueue()
    {
        return sendQueue;
    }

    @Override
    public ServerSessionImpl session()
    {
        return session;
    }

    @Override
    public void session(ServerSessionImpl session)
    {
        this.session = session;
    }
}
