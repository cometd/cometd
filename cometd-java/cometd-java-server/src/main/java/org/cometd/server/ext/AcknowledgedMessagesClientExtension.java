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

package org.cometd.server.ext;

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
 * Acknowledged Message Client extension.
 *
 * Tracks the batch id of messages sent to a client.
 *
 */
public class AcknowledgedMessagesClientExtension implements Extension
{
    private final Logger _logger = LoggerFactory.getLogger(getClass().getName());
    private final ServerSessionImpl _session;
    private final Object _lock;
    private final ArrayIdQueue<ServerMessage> _unackedQueue;
    private long _lastAck;

    public AcknowledgedMessagesClientExtension(ServerSession session)
    {
        _session = (ServerSessionImpl)session;
        _lock = _session.getLock();
        synchronized (_lock)
        {
            Queue<ServerMessage> queue = _session.getQueue();
            _unackedQueue = new ArrayIdQueue<>(16, 32, queue);
            _unackedQueue.setCurrentId(1);
        }
    }

    public boolean rcv(ServerSession from, Mutable message)
    {
        return true;
    }

    public boolean rcvMeta(ServerSession session, Mutable message)
    {
        if (Channel.META_CONNECT.equals(message.getChannel()))
        {
            Map<String,Object> ext = message.getExt(false);
            if (ext != null)
            {
                assert session == _session;

                synchronized(_lock)
                {
                    Number ackValue = (Number)ext.get("ack");
                    _logger.debug("Session {} received ack {}, lastAck {}", session, ackValue, _lastAck);
                    if (ackValue != null)
                    {
                        long acked = ackValue.longValue();
                        if (acked <=_lastAck)
                        {
                            _session.replaceQueue(_unackedQueue);
                        }
                        else
                        {
                            _lastAck = acked;

                            // We have received an ack ID, so delete the acked
                            // messages.
                            final int s = _unackedQueue.size();
                            if (s > 0)
                            {
                                if (_unackedQueue.getAssociatedIdUnsafe(s - 1) <= acked)
                                {
                                    // we can just clear the queue
                                    _unackedQueue.clear();
                                }
                                else
                                {
                                    // we need to remove elements until we see unacked
                                    for (int i = 0; i < s; ++i)
                                    {
                                        final long a = _unackedQueue.getAssociatedIdUnsafe(0);
                                        if (a <= acked)
                                        {
                                            _unackedQueue.remove();
                                            continue;
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return true;
    }

    public ServerMessage send(ServerSession to, ServerMessage message)
    {
        if (message.containsKey(Message.DATA_FIELD))
        {
            synchronized (_lock)
            {
                _unackedQueue.add(message);
            }
        }
        return message;
    }

    public boolean sendMeta(ServerSession to, Mutable message)
    {
        if (message.getChannel().equals(Channel.META_CONNECT))
        {
            synchronized (_lock)
            {
                Map<String,Object> ext = message.getExt(true);
                ext.put("ack", _unackedQueue.getCurrentId());
                _unackedQueue.incrementCurrentId();
            }
        }
        return true;
    }
}


