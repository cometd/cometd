/*
 * Copyright (c) 2008-2020 the original author or authors.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSession;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MessageProcessingOrderTest {
    private final BayeuxServerImpl _bayeux = new BayeuxServerImpl();

    @BeforeEach
    public void init() throws Exception {
        _bayeux.start();
    }

    @AfterEach
    public void destroy() throws Exception {
        _bayeux.stop();
    }

    @Test
    public void testProcessingOrderClientPublishBroadcast() {
        Queue<String> events = new ConcurrentLinkedQueue<>();

        LocalSession session0 = _bayeux.newLocalSession("s0");
        session0.handshake();
        LocalSession session1 = _bayeux.newLocalSession("s1");
        session1.handshake();

        String channelName = "/foo/bar";

        // Client-side extension for the sender.
        session0.addExtension(new ClientExtension(events, "0"));
        // Client receiving the publish reply.
        session0.getChannel(channelName).addListener(new ClientListener(events, "0"));
        // Client-side extension for the subscriber.
        session1.addExtension(new ClientExtension(events, "1"));
        // Subscriber receiving the published message.
        session1.getChannel(channelName).subscribe(new ClientListener(events, "1"));
        // Server extension.
        _bayeux.addExtension(new ServerExtension(events));
        // Server-side session extension for the sender.
        session0.getServerSession().addExtension(new ServerSessionExtension(events, "0"));
        // Server-side session extension for the subscriber.
        session1.getServerSession().addExtension(new ServerSessionExtension(events, "1"));
        // Server-side channel message listener.
        _bayeux.getChannel(channelName).addListener(new ServerListener(events));
        // Server-side session message listener.
        session0.getServerSession().addListener(new ServerSessionListener(events, "0"));
        session1.getServerSession().addListener(new ServerSessionListener(events, "1"));

        session0.getChannel(channelName).publish("data");

        List<String> expected = Arrays.asList(
                "0.cln.ext.snd",
                "srv.ext.rcv",
                "0.srv.ssn.ext.rcv",
                "srv.chn.lst",
                "srv.ext.snd",
                "1.srv.ssn.ext.snd",
                "1.srv.ssn.lst",
                "1.cln.ext.rcv",
                "1.cln.lst",
                "srv.ext.snd.rpy",
                "0.srv.ssn.ext.snd.rpy",
                "0.cln.ext.rcv.rpy",
                "0.cln.lst.rpy"
        );
        Assertions.assertEquals(expected, new ArrayList<>(events));
    }

    @Test
    public void testProcessingOrderClientPublishServiceServerDeliver() {
        Queue<String> events = new ConcurrentLinkedQueue<>();

        LocalSession client = _bayeux.newLocalSession("cln");
        client.handshake();
        LocalSession service = _bayeux.newLocalSession("svc");
        service.handshake();

        String channelName = "/service/foo";

        client.addExtension(new ClientExtension(events, "0"));
        client.getChannel(channelName).addListener(new ClientListener(events, "0"));
        _bayeux.addExtension(new ServerExtension(events));
        client.getServerSession().addExtension(new ServerSessionExtension(events, "0"));
        service.getServerSession().addExtension(new ServerSessionExtension(events, "1"));
        ServerChannel channel = _bayeux.createChannelIfAbsent(channelName).getReference();
        channel.addListener(new ServerListener(events));
        // This is how services are typically implemented
        channel.addListener(new ServerChannel.MessageListener() {
            @Override
            public void onMessage(ServerSession session, ServerChannel channel, ServerMessage.Mutable message, Promise<Boolean> promise) {
                events.offer("svc.chn.lst");
                session.deliver(service, channelName, message.getData(), promise);
            }
        });
        client.getServerSession().addListener(new ServerSessionListener(events, "0"));
        service.getServerSession().addListener(new ServerSessionListener(events, "1"));
        service.addExtension(new ClientExtension(events, "1"));
        service.getChannel(channelName).subscribe(new ClientListener(events, "1"));

        client.getChannel(channelName).publish("data");

        List<String> expected = Arrays.asList(
                "0.cln.ext.snd",
                "srv.ext.rcv",
                "0.srv.ssn.ext.rcv",
                "srv.chn.lst",
                "svc.chn.lst",
                "srv.ext.snd",
                "0.srv.ssn.ext.snd",
                "0.srv.ssn.lst",
                "0.cln.ext.rcv",
                "0.cln.lst",
                "srv.ext.snd.rpy",
                "0.srv.ssn.ext.snd.rpy",
                "0.cln.ext.rcv.rpy",
                "0.cln.lst.rpy"
        );
        Assertions.assertEquals(expected, new ArrayList<>(events));
    }

    @Test
    public void testProcessingOrderClientPublishServiceServerPublish() {
        Queue<String> events = new ConcurrentLinkedQueue<>();

        LocalSession session0 = _bayeux.newLocalSession("s0");
        session0.handshake();
        LocalSession session1 = _bayeux.newLocalSession("s1");
        session1.handshake();
        LocalSession service = _bayeux.newLocalSession("svc");
        service.handshake();

        String serviceChannel = "/service/foo";
        String broadcastChannel = "/foo";

        session0.addExtension(new ClientExtension(events, "0"));
        session0.getChannel(serviceChannel).addListener(new ClientListener(events, "0"));
        session1.addExtension(new ClientExtension(events, "1"));
        session1.getChannel(serviceChannel).addListener(new ClientListener(events, "1"));
        session1.getChannel(broadcastChannel).subscribe(new ClientListener(events, "1"), new ClientCallback(events, "1.sub"));
        _bayeux.addExtension(new ServerExtension(events));
        session0.getServerSession().addExtension(new ServerSessionExtension(events, "0"));
        session1.getServerSession().addExtension(new ServerSessionExtension(events, "1"));
        service.getServerSession().addExtension(new ServerSessionExtension(events, "1"));
        ServerChannel channel = _bayeux.createChannelIfAbsent(serviceChannel).getReference();
        channel.addListener(new ServerListener(events));
        // Services are typically implemented with a ServerChannel.MessageListener.
        channel.addListener(new ServerChannel.MessageListener() {
            @Override
            public void onMessage(ServerSession sender, ServerChannel channel, ServerMessage.Mutable message, Promise<Boolean> promise) {
                events.offer("svc.chn.lst");
                _bayeux.createChannelIfAbsent(broadcastChannel).getReference().publish(service, message.getData(), promise);
            }
        });
        session0.getServerSession().addListener(new ServerSessionListener(events, "0"));
        session1.getServerSession().addListener(new ServerSessionListener(events, "1"));
        service.getServerSession().addListener(new ServerSessionListener(events, "1"));
        service.addExtension(new ClientExtension(events, "1"));
        service.getChannel(serviceChannel).subscribe(new ClientListener(events, "1"));

        session0.getChannel(serviceChannel).publish("data", new ClientCallback(events, "0.pub"));

        List<String> expected = Arrays.asList(
                "1.sub.cln.cbk",
                "0.cln.ext.snd",
                "srv.ext.rcv",
                "0.srv.ssn.ext.rcv",
                "srv.chn.lst",
                "svc.chn.lst",
                "srv.ext.snd",
                "1.srv.ssn.ext.snd",
                "1.srv.ssn.lst",
                "1.cln.ext.rcv",
                "1.cln.lst",
                "srv.ext.snd.rpy",
                "0.srv.ssn.ext.snd.rpy",
                "0.cln.ext.rcv.rpy",
                "0.pub.cln.cbk",
                "0.cln.lst.rpy"
        );
        Assertions.assertEquals(expected, new ArrayList<>(events));
    }

    private static class ClientListener implements ClientSessionChannel.MessageListener {
        private final Queue<String> events;
        private final String id;

        private ClientListener(Queue<String> events, String id) {
            this.events = events;
            this.id = id;
        }

        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            if (message.isPublishReply()) {
                events.offer(id + ".cln.lst.rpy");
            } else {
                events.offer(id + ".cln.lst");
            }
        }
    }

    private static class ClientExtension implements ClientSession.Extension {
        private final Queue<String> events;
        private final String id;

        private ClientExtension(Queue<String> events, String id) {
            this.events = events;
            this.id = id;
        }

        @Override
        public boolean send(ClientSession session, Message.Mutable message) {
            events.offer(id + ".cln.ext.snd");
            return true;
        }

        @Override
        public boolean rcv(ClientSession session, Message.Mutable message) {
            if (message.isPublishReply()) {
                events.offer(id + ".cln.ext.rcv.rpy");
            } else {
                events.offer(id + ".cln.ext.rcv");
            }
            return true;
        }
    }

    private static class ClientCallback implements ClientSession.MessageListener {
        private final Queue<String> events;
        private final String id;

        private ClientCallback(Queue<String> events, String id) {
            this.events = events;
            this.id = id;
        }

        @Override
        public void onMessage(Message message) {
            if (message.isMeta() || message.isPublishReply()) {
                events.offer(id + ".cln.cbk");
            }
        }
    }

    private static class ServerExtension implements BayeuxServer.Extension {
        private final Queue<String> events;

        private ServerExtension(Queue<String> events) {
            this.events = events;
        }

        @Override
        public boolean rcv(ServerSession from, ServerMessage.Mutable message) {
            events.offer("srv.ext.rcv");
            return true;
        }

        @Override
        public boolean send(ServerSession from, ServerSession to, ServerMessage.Mutable message) {
            if (message.isPublishReply()) {
                events.offer("srv.ext.snd.rpy");
            } else {
                events.offer("srv.ext.snd");
            }
            return true;
        }
    }

    private static class ServerSessionExtension implements ServerSession.Extension {
        private final Queue<String> events;
        private final String id;

        private ServerSessionExtension(Queue<String> events, String id) {
            this.events = events;
            this.id = id;
        }

        @Override
        public boolean rcv(ServerSession session, ServerMessage.Mutable message) {
            events.offer(id + ".srv.ssn.ext.rcv");
            return true;
        }

        @Override
        public ServerMessage send(ServerSession sender, ServerSession session, ServerMessage message) {
            if (message.isPublishReply()) {
                events.offer(id + ".srv.ssn.ext.snd.rpy");
            } else {
                events.offer(id + ".srv.ssn.ext.snd");
            }
            return message;
        }
    }

    private static class ServerListener implements ServerChannel.MessageListener {
        private final Queue<String> events;

        private ServerListener(Queue<String> events) {
            this.events = events;
        }

        @Override
        public boolean onMessage(ServerSession from, ServerChannel channel, ServerMessage.Mutable message) {
            events.offer("srv.chn.lst");
            return true;
        }
    }

    private static class ServerSessionListener implements ServerSession.MessageListener {
        private final Queue<String> events;
        private final String id;

        private ServerSessionListener(Queue<String> events, String id) {
            this.events = events;
            this.id = id;
        }

        @Override
        public boolean onMessage(ServerSession session, ServerSession sender, ServerMessage message) {
            events.offer(id + ".srv.ssn.lst");
            return true;
        }
    }
}
