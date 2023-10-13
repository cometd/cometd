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
package org.cometd.server.http;

import java.util.ArrayList;
import java.util.List;

import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.server.CometDRequest;
import org.cometd.server.CometDResponse;
import org.cometd.server.ServerSessionImpl;

public class TransportContext
{
    private final BayeuxContext bayeuxContext;
    private final CometDRequest cometDRequest;
    private final CometDResponse cometDResponse;
    private final Promise<Void> promise;
    private final List<ServerMessage.Mutable> replies = new ArrayList<>();

    private List<ServerMessage.Mutable> messages;
    private long metaConnectCycle;
    private boolean scheduleExpiration;
    private AbstractHttpTransport.HttpScheduler scheduler;
    private boolean sendQueue;
    private ServerSessionImpl session;

    public TransportContext(BayeuxContext bayeuxContext, CometDRequest cometDRequest, CometDResponse cometDResponse, Promise<Void> promise)
    {
        this.bayeuxContext = bayeuxContext;
        this.cometDRequest = cometDRequest;
        this.cometDResponse = cometDResponse;
        this.promise = promise;
    }

    public BayeuxContext bayeuxContext()
    {
        return bayeuxContext;
    }

    public CometDRequest request()
    {
        return cometDRequest;
    }

    public CometDResponse response()
    {
        return cometDResponse;
    }

    public Promise<Void> promise() {
        return promise;
    }

    public List<ServerMessage.Mutable> messages()
    {
        return messages;
    }

    public void messages(List<ServerMessage.Mutable> messages)
    {
        this.messages = messages;
    }

    public long metaConnectCycle()
    {
        return metaConnectCycle;
    }

    public void metaConnectCycle(long l)
    {
        this.metaConnectCycle = l;
    }

    public List<ServerMessage.Mutable> replies()
    {
        return replies;
    }

    public void scheduleExpiration(boolean b)
    {
        this.scheduleExpiration = b;
    }

    public boolean scheduleExpiration()
    {
        return scheduleExpiration;
    }

    public AbstractHttpTransport.HttpScheduler scheduler()
    {
        return scheduler;
    }

    public void scheduler(AbstractHttpTransport.HttpScheduler httpScheduler)
    {
        this.scheduler = httpScheduler;
    }

    public void sendQueue(boolean b)
    {
        this.sendQueue = b;
    }

    public boolean sendQueue()
    {
        return sendQueue;
    }

    public ServerSessionImpl session()
    {
        return session;
    }

    public void session(ServerSessionImpl session)
    {
        this.session = session;
    }
}
