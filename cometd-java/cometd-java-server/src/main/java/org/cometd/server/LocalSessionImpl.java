/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.server;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.ChannelId;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.AbstractClientSession;

/**
 * <p>A {@link LocalSession} implementation.</p>
 * <p>This {@link LocalSession} implementation communicates with its
 * {@link ServerSession} counterpart without any serialization.</p>
 */
public class LocalSessionImpl extends AbstractClientSession implements LocalSession {
    private final Queue<ServerMessage.Mutable> _queue = new ConcurrentLinkedQueue<>();
    private final BayeuxServerImpl _bayeux;
    private final String _idHint;
    private ServerSessionImpl _session;

    protected LocalSessionImpl(BayeuxServerImpl bayeux, String idHint) {
        _bayeux = bayeux;
        _idHint = idHint;
    }

    @Override
    public void receive(Message.Mutable message, Promise<Void> promise) {
        super.receive(message, Promise.from(y -> {
            if (Channel.META_DISCONNECT.equals(message.getChannel()) && message.isSuccessful()) {
                _session = null;
            }
            promise.succeed(null);
        }, promise::fail));
    }

    @Override
    protected AbstractSessionChannel newChannel(ChannelId channelId) {
        return new LocalChannel(channelId);
    }

    @Override
    protected ChannelId newChannelId(String channelId) {
        return _bayeux.newChannelId(channelId);
    }

    @Override
    protected void sendBatch() {
        int size = _queue.size();
        while (size-- > 0) {
            ServerMessage.Mutable message = _queue.poll();
            doSend(_session, message, Promise.noop());
        }
    }

    @Override
    public ServerSession getServerSession() {
        if (_session == null) {
            throw new IllegalStateException("Method handshake() not invoked for local session " + this);
        }
        return _session;
    }

    @Override
    public void handshake(Map<String, Object> template, ClientSession.MessageListener callback) {
        if (_session != null) {
            throw new IllegalStateException();
        }

        ServerSessionImpl session = new ServerSessionImpl(_bayeux, this, _idHint);

        ServerMessage.Mutable hsMessage = newMessage();
        if (template != null) {
            hsMessage.putAll(template);
        }
        String messageId = newMessageId();
        hsMessage.setId(messageId);
        hsMessage.setChannel(Channel.META_HANDSHAKE);
        registerCallback(messageId, callback);

        doSend(session, hsMessage, new Promise<ServerMessage.Mutable>() {
            @Override
            public void succeed(ServerMessage.Mutable hsReply) {
                if (hsReply != null && hsReply.isSuccessful()) {
                    _session = session;

                    ServerMessage.Mutable cnMessage = newMessage();
                    cnMessage.setId(newMessageId());
                    cnMessage.setChannel(Channel.META_CONNECT);
                    cnMessage.getAdvice(true).put(Message.INTERVAL_FIELD, -1L);
                    cnMessage.setClientId(session.getId());

                    doSend(session, cnMessage, Promise.from(cnReply -> {}, this::fail));
                }
            }

            @Override
            public void fail(Throwable failure) {
                // TODO
            }
        });
    }

    @Override
    public void disconnect(ClientSession.MessageListener callback) {
        if (_session != null) {
            ServerMessage.Mutable message = newMessage();
            String messageId = newMessageId();
            message.setId(messageId);
            message.setChannel(Channel.META_DISCONNECT);
            message.setClientId(_session.getId());
            registerCallback(messageId, callback);
            send(message);
            while (isBatching()) {
                endBatch();
            }
        }
    }

    @Override
    public String getId() {
        if (_session == null) {
            throw new IllegalStateException("Method handshake() not invoked for local session " + this);
        }
        return _session.getId();
    }

    @Override
    public boolean isConnected() {
        return _session != null && _session.isConnected();
    }

    @Override
    public boolean isHandshook() {
        return _session != null && _session.isHandshook();
    }

    @Override
    public String toString() {
        return "L:" + (_session == null ? _idHint + "_<disconnected>" : _session.getId());
    }

    @Override
    protected void send(Message.Mutable message) {
        if (message instanceof ServerMessage.Mutable) {
            send(_session, (ServerMessage.Mutable)message);
        } else {
            ServerMessage.Mutable mutable = newMessage();
            mutable.putAll(message);
            send(_session, mutable);
        }
    }

    /**
     * <p>Enqueues or sends a message to the server.</p>
     * <p>This method will either enqueue the message, if this session {@link #isBatching() is batching},
     * or perform the send immediately.</p>
     *
     * @param session The ServerSession to send as. This normally the current server session, but during handshake it is a proposed server session.
     * @param message The message to send.
     */
    protected void send(ServerSessionImpl session, ServerMessage.Mutable message) {
        if (isBatching()) {
            _queue.add(message);
        } else {
            doSend(session, message, Promise.noop());
        }
    }

    private void doSend(ServerSessionImpl session, ServerMessage.Mutable message, Promise<ServerMessage.Mutable> promise) {
        String messageId = message.getId();
        message.setClientId(session.getId());
        extendOutgoing(message, Promise.from(result -> {
            // Extensions may have changed the messageId.
            message.setId(messageId);
            if (result) {
                _bayeux.handle(session, message, Promise.from(r ->
                        _bayeux.extendReply(session, _session, r, Promise.from(reply -> {
                            if (reply != null) {
                                receive(reply, Promise.from(y -> promise.succeed(reply), promise::fail));
                            } else {
                                promise.succeed(null);
                            }
                        }, promise::fail)), promise::fail));
            } else {
                ServerMessage.Mutable reply = _bayeux.createReply(message);
                _bayeux.error(reply, "404::message_deleted");
                receive(reply, Promise.from(y -> promise.succeed(reply), promise::fail));
                promise.succeed(reply);
            }
        }, promise::fail));
    }

    @Override
    protected ServerMessage.Mutable newMessage() {
        return _bayeux.newMessage();
    }

    /**
     * <p>A channel scoped to this LocalSession.</p>
     */
    protected class LocalChannel extends AbstractSessionChannel {
        protected LocalChannel(ChannelId channelId) {
            super(channelId);
        }

        @Override
        public ClientSession getSession() {
            throwIfReleased();
            return LocalSessionImpl.this;
        }
    }
}
