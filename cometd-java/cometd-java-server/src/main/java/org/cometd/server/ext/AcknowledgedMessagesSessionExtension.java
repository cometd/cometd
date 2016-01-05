/*
 * Copyright (c) 2008-2016 the original author or authors.
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
package org.cometd.server.ext;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerMessage.Mutable;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.bayeux.server.ServerSession.Extension;
import org.cometd.server.ServerSessionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks the batch id of messages sent to a client.
 */
public class AcknowledgedMessagesSessionExtension implements Extension, ServerSession.DeQueueListener, ServerSession.QueueListener
{
    private static final Logger _logger = LoggerFactory.getLogger(AcknowledgedMessagesSessionExtension.class);

    private final Map<Long, Long> _batches = new HashMap<>();
    private final ServerSessionImpl _session;
    private final BatchArrayQueue<ServerMessage> _queue;
    private long _lastBatch;

    public AcknowledgedMessagesSessionExtension(ServerSession session)
    {
        _session = (ServerSessionImpl)session;
        _queue = new BatchArrayQueue<>(16, _session.getLock());
        _session.setMetaConnectDeliveryOnly(true);
        _session.addListener(this);
    }

    public boolean rcv(ServerSession from, Mutable message)
    {
        return true;
    }

    public boolean rcvMeta(ServerSession session, Mutable message)
    {
        if (Channel.META_CONNECT.equals(message.getChannel()))
        {
            Map<String, Object> ext = message.getExt(false);
            if (ext != null)
            {
                Number batchValue = (Number)ext.get("ack");
                if (batchValue != null)
                {
                    processBatch(batchValue.longValue());
                    updateAdvice(message);
                }
            }
        }
        return true;
    }

    private void updateAdvice(Mutable message)
    {
        synchronized (_session.getLock())
        {
            if (!_session.hasNonLazyMessages() && _session.getQueue().size() != _queue.size())
            {
                Map<String, Object> advice = message.getAdvice(true);
                if (advice.get(Message.TIMEOUT_FIELD) == null)
                {
                    advice.put(Message.TIMEOUT_FIELD, 0L);
                    if (_logger.isDebugEnabled())
                        _logger.debug("Forcing advice: { timeout: 0 } for {}", _session);
                }
            }
        }
    }

    protected void processBatch(long batch)
    {
        synchronized (_session.getLock())
        {
            if (_logger.isDebugEnabled())
                _logger.debug("Processing batch: last={}, client={}, server={} for {}", _lastBatch, batch, _queue.getBatch(), _session);
            _lastBatch = batch;
            _queue.clearToBatch(batch);
        }
    }

    public ServerMessage send(ServerSession session, ServerMessage message)
    {
        // Too early to do anything with the message.
        // Other extensions and/or listener may modify/veto it.
        return message;
    }

    public void queued(ServerSession sender, ServerMessage message)
    {
        // This method is called after all the extensions and the other
        // listeners, so only here are sure that the message is not vetoed.
        synchronized (_session.getLock())
        {
            _queue.offer(message);
            if (_logger.isDebugEnabled())
                _logger.debug("Stored at batch {} {} for {}", _queue.getBatch(), message, _session);
        }
    }

    public boolean sendMeta(ServerSession to, Mutable message)
    {
        if (message.getChannel().equals(Channel.META_CONNECT))
        {
            synchronized (_session.getLock())
            {
                Map<String, Object> ext = message.getExt(true);
                long batch = _queue.getBatch();
                _batches.put(Thread.currentThread().getId(), batch);
                if (_logger.isDebugEnabled())
                    _logger.debug("Sending batch {} for {}", batch, _session);
                ext.put("ack", batch);
                _queue.nextBatch();
            }
        }
        return true;
    }

    public void deQueue(ServerSession session, Queue<ServerMessage> queue)
    {
        synchronized (_session.getLock())
        {
            Long batch = _batches.remove(Thread.currentThread().getId());
            if (_logger.isDebugEnabled())
                _logger.debug("Dequeuing {}/{} messages until batch {} for {}", queue.size(), _queue.size(), batch, _session);
            queue.clear();
            _queue.exportMessagesToBatch(queue, batch);
        }
    }

    protected void importMessages(ServerSessionImpl session)
    {
        synchronized (_session.getLock())
        {
            _queue.addAll(session.getQueue());
        }
    }

    // Used only in tests.
    BatchArrayQueue<ServerMessage> getBatchArrayQueue()
    {
        return _queue;
    }
}
